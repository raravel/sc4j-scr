package org.fogofwarlabs.screcorder.format.recording;

import org.fogofwarlabs.screcorder.exception.Exceptions;
import org.fogofwarlabs.screcorder.format.recording.metadata.RecordMetadata;
import org.fogofwarlabs.screcorder.format.recording.observation.CaptureConsistency;
import org.fogofwarlabs.screcorder.format.recording.observation.SampleStatus;
import org.fogofwarlabs.screcorder.format.recording.observation.StorageMode;
import org.fogofwarlabs.screcorder.format.recording.summary.FrameRange;
import org.fogofwarlabs.screcorder.format.recording.summary.RecordSummary;
import org.fogofwarlabs.screcorder.format.recording.summary.Termination;
import org.fogofwarlabs.screcorder.sample.Sample;
import org.fogofwarlabs.screcorder.sample.SampleDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Stages complete typed frames and writes {@code SCRECBIN 0.1} records.
 * <p>
 * This writer owns its output stream. Closing without {@link #finish(Termination)} leaves an incomplete record and never
 * invents normal termination. Instances are confined to one thread.
 */
public final class RecordWriter implements Closeable {
    private static final int INITIAL_BUFFER_BYTES = 4096;

    /**
     * Creates a {@link RecordWriter} instance for a new path.
     *
     * @param path        the destination path, which must not exist
     * @param definitions the selected canonical sample definitions
     * @param metadata    the capture provenance and requirements
     * @param storageMode the default available sample representation
     * @return the created {@link RecordWriter} instance
     * @throws IOException if creation or header writing fails
     */
    public static @NotNull RecordWriter open(@NotNull Path path, @NotNull List<SampleDefinition<?>> definitions,
                                             @NotNull RecordMetadata metadata, @NotNull StorageMode storageMode) throws IOException {
        RecordFormatUtils.validateDefinitions(definitions);

        return new RecordWriter(new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)),
                                definitions, metadata, storageMode);
    }


    private final @NotNull OutputStream output;
    private final @NotNull Record record;
    private final @NotNull StorageMode defaultStorageMode;
    private final @NotNull RecordQuality quality;

    private final @NotNull CRC32C checksum;
    private final @NotNull SampleDigester digester;
    private final byte @NotNull [] chunkHeader;
    private final int @NotNull [] sectionOffsets;
    private final int @NotNull [] sectionLengths;

    private byte @NotNull [] staging;
    private byte @NotNull [] committed;
    private int stagedBytes;
    private int stagedSections;
    private long stagedFrame;
    private long stagedElapsedNanoseconds;
    private @NotNull CaptureConsistency stagedConsistency;
    private long stagedUnavailableSamples;
    private long sequence;
    private final long startNanoseconds;
    private boolean activeFrame;
    private boolean stageFaulted;
    private boolean outputFaulted;
    private boolean finished;
    private boolean closed;
    private @Nullable RecordSummary summary;

    /**
     * Creates a {@link RecordWriter} instance and writes its immutable header.
     * <p>
     * Ownership of the supplied stream transfers immediately, including constructor failure.
     *
     * @param output      the owned output stream
     * @param definitions the selected canonical sample definitions
     * @param metadata    the capture provenance and requirements
     * @param storageMode the default available sample representation
     * @throws IOException if definitions are invalid or the header cannot be written
     */
    public RecordWriter(@NotNull OutputStream output, @NotNull List<SampleDefinition<?>> definitions,
                        @NotNull RecordMetadata metadata, @NotNull StorageMode storageMode) throws IOException {
        this.output = output;

        try {
            if (storageMode == StorageMode.NONE || (storageMode == StorageMode.DIGEST && !metadata.isDigestEnabled())) {
                throw Exceptions.newIOException("The default storage mode must support available samples");
            }

            this.record             = new Record(RecordFormatUtils.validateDefinitions(definitions), metadata);
            this.defaultStorageMode = storageMode;
            this.quality            = new RecordQuality(metadata);

            this.checksum       = new CRC32C();
            this.digester       = new SampleDigester();
            this.chunkHeader    = new byte[RecordFormatUtils.CHUNK_HEADER_BYTES];
            this.sectionOffsets = new int[this.record.getDefinitions().size()];
            this.sectionLengths = new int[this.record.getDefinitions().size()];

            this.staging                  = new byte[INITIAL_BUFFER_BYTES];
            this.committed                = new byte[INITIAL_BUFFER_BYTES];
            this.stagedBytes              = 0;
            this.stagedSections           = 0;
            this.stagedFrame              = -1;
            this.stagedElapsedNanoseconds = 0;
            this.stagedConsistency        = CaptureConsistency.UNVERIFIED;
            this.stagedUnavailableSamples = 0;
            this.sequence                 = 0;
            this.startNanoseconds         = System.nanoTime();
            this.activeFrame              = false;
            this.stageFaulted             = false;
            this.outputFaulted            = false;
            this.finished                 = false;
            this.closed                   = false;
            this.summary                  = null;

            this.output.write(RecordFormatUtils.encodeHeader(this.record));
        } catch (IOException | RuntimeException exception) {
            try {
                output.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }

            throw exception;
        }
    }

    /**
     * @return the immutable session descriptor
     */
    public @NotNull Record getRecord() {
        return this.record;
    }

    /**
     * @return the validated final summary, or {@code null} before successful finalization
     */
    public @Nullable RecordSummary getSummary() {
        return this.summary;
    }


    /**
     * Begins a frame using this writer's monotonic elapsed time.
     *
     * @param frameNumber the observed frame number
     * @param consistency the observation consistency evidence
     * @throws IOException           if frame ordering is invalid
     * @throws IllegalStateException if the writer is closed or finalized, or a frame is already staged
     */
    public void beginFrame(long frameNumber, @NotNull CaptureConsistency consistency) throws IOException {
        this.beginFrame(frameNumber, System.nanoTime() - this.startNanoseconds, consistency);
    }

    /**
     * Begins a frame with explicit monotonic elapsed time.
     *
     * @param frameNumber        the observed frame number
     * @param elapsedNanoseconds the nonnegative elapsed time
     * @param consistency        the observation consistency evidence
     * @throws IOException           if frame ordering or elapsed time is invalid
     * @throws IllegalStateException if the writer is closed or finalized, or a frame is already staged
     */
    public void beginFrame(long frameNumber, long elapsedNanoseconds, @NotNull CaptureConsistency consistency) throws IOException {
        this.requireWritable();

        if (this.activeFrame) {
            throw Exceptions.newIllegalStateException("A frame is already staged");
        }

        this.quality.validateFrame(frameNumber, elapsedNanoseconds);
        Arrays.fill(this.sectionOffsets, -1);
        Arrays.fill(this.sectionLengths, 0);

        this.stagedBytes              = 0;
        this.stagedSections           = 0;
        this.stagedFrame              = frameNumber;
        this.stagedElapsedNanoseconds = elapsedNanoseconds;
        this.stagedConsistency        = consistency;
        this.stagedUnavailableSamples = 0;
        this.activeFrame              = true;
        this.stageFaulted             = false;
    }

    /**
     * Appends a typed sample using the default representation.
     *
     * @param definition    the selected sample definition
     * @param sample        the immutable sample value
     * @param <SampleValue> the sample type bound to the definition
     * @throws IOException           if encoding or bounds validation fails
     * @throws IllegalStateException if the writer is closed or finalized, or no frame is staged or the stage has failed
     */
    public <SampleValue extends Sample> void append(@NotNull SampleDefinition<SampleValue> definition, @NotNull SampleValue sample) throws IOException {
        this.append(definition, sample, this.defaultStorageMode);
    }

    /**
     * Appends a typed sample using an explicit representation.
     * <p>
     * A failed append faults the current stage until {@link #abortFrame()}.
     *
     * @param definition    the selected sample definition
     * @param sample        the immutable sample value
     * @param storageMode   the available sample representation
     * @param <SampleValue> the sample type bound to the definition
     * @throws IOException           if encoding or bounds validation fails
     * @throws IllegalStateException if the writer is closed or finalized, or no frame is staged or the stage has failed
     */
    public <SampleValue extends Sample> void append(@NotNull SampleDefinition<SampleValue> definition, @NotNull SampleValue sample,
                                                    @NotNull StorageMode storageMode) throws IOException {
        this.requireStage();

        try {
            int sectionIndex = this.findSectionIndex(definition);

            if (!definition.getSampleClass().isInstance(sample) || storageMode == StorageMode.NONE ||
                (storageMode == StorageMode.DIGEST && !this.record.getMetadata().isDigestEnabled())) {
                throw Exceptions.newIOException("Invalid sample type or storage mode");
            }

            int valueBytes = RecordFormatUtils.getValueBytes(definition, sample);
            int payloadBytes = storageMode == StorageMode.VALUES ? valueBytes : RecordFormatUtils.DIGEST_BYTES;
            long nextBytes = (long) this.stagedBytes + RecordFormatUtils.SAMPLE_HEADER_BYTES + payloadBytes;

            if (nextBytes > RecordFormatUtils.MAXIMUM_CHUNK_BYTES - RecordFormatUtils.FRAME_PREFIX_BYTES) {
                throw Exceptions.newIOException("Frame exceeds the maximum chunk size");
            }

            this.staging = RecordFormatUtils.growBuffer(this.staging, (int) nextBytes);

            ByteBuffer section = ByteBuffer.wrap(this.staging).order(ByteOrder.LITTLE_ENDIAN);

            section.position(this.stagedBytes);
            section.putInt(definition.getIdentifier());
            section.put((byte) storageMode.getCode()).put((byte) SampleStatus.AVAILABLE.getCode());
            section.putLong(this.stagedFrame).putInt(payloadBytes);

            if (storageMode == StorageMode.VALUES) {
                ByteBuffer values = section.slice(section.position(), valueBytes).order(ByteOrder.LITTLE_ENDIAN);

                RecordFormatUtils.writeValues(definition, sample, values, valueBytes);
            } else {
                this.digester.digest(definition, sample, this.staging, section.position());
            }

            this.sectionOffsets[sectionIndex] = this.stagedBytes;
            this.sectionLengths[sectionIndex] = RecordFormatUtils.SAMPLE_HEADER_BYTES + payloadBytes;

            this.stagedBytes = (int) nextBytes;
            this.stagedSections++;
        } catch (IOException | RuntimeException exception) {
            this.stageFaulted = true;

            throw exception;
        }
    }

    /**
     * Appends an explicitly unavailable observation.
     *
     * @param definition the selected definition
     * @param status     the failure or not-attempted status
     * @throws IOException           if the observation is invalid or exceeds bounds
     * @throws IllegalStateException if the writer is closed or finalized, or no frame is staged or the stage has failed
     */
    public void appendUnavailable(@NotNull SampleDefinition<?> definition, @NotNull SampleStatus status) throws IOException {
        this.requireStage();

        try {
            int sectionIndex = this.findSectionIndex(definition);

            if (status == SampleStatus.AVAILABLE) {
                throw Exceptions.newIOException("Unavailable observations require an unavailable status");
            }

            int nextBytes = this.stagedBytes + RecordFormatUtils.SAMPLE_HEADER_BYTES;

            if (nextBytes > RecordFormatUtils.MAXIMUM_CHUNK_BYTES - RecordFormatUtils.FRAME_PREFIX_BYTES) {
                throw Exceptions.newIOException("Frame exceeds the maximum chunk size");
            }

            this.staging = RecordFormatUtils.growBuffer(this.staging, nextBytes);

            ByteBuffer section = ByteBuffer.wrap(this.staging).order(ByteOrder.LITTLE_ENDIAN);

            section.position(this.stagedBytes);
            section.putInt(definition.getIdentifier());
            section.put((byte) StorageMode.NONE.getCode()).put((byte) status.getCode()).putLong(-1).putInt(0);

            this.sectionOffsets[sectionIndex] = this.stagedBytes;
            this.sectionLengths[sectionIndex] = RecordFormatUtils.SAMPLE_HEADER_BYTES;

            this.stagedBytes = nextBytes;
            this.stagedSections++;
            this.stagedUnavailableSamples++;
        } catch (IOException | RuntimeException exception) {
            this.stageFaulted = true;

            throw exception;
        }
    }

    /**
     * Commits the staged frame after every selected sample has one observation.
     *
     * @throws IOException           if a sample is missing or file output fails
     * @throws IllegalStateException if the writer is closed or finalized, or no frame is staged or the stage has failed
     */
    public void endFrame() throws IOException {
        this.requireStage();

        if (this.stagedSections != this.record.getDefinitions().size()) {
            this.stageFaulted = true;

            throw Exceptions.newIOException("Every selected sample requires one observation");
        }

        this.quality.validateObservationCounts(this.stagedUnavailableSamples);

        int payloadBytes = RecordFormatUtils.FRAME_PREFIX_BYTES + this.stagedBytes;

        this.committed = RecordFormatUtils.growBuffer(this.committed, payloadBytes);

        ByteBuffer frame = ByteBuffer.wrap(this.committed).order(ByteOrder.LITTLE_ENDIAN);

        frame.putLong(this.stagedFrame).putLong(this.stagedElapsedNanoseconds).putInt(this.stagedSections).putInt(this.stagedConsistency.getCode());

        for (int sectionIndex = 0; sectionIndex < this.sectionOffsets.length; sectionIndex++) {
            frame.put(this.staging, this.sectionOffsets[sectionIndex], this.sectionLengths[sectionIndex]);
        }

        this.writeChunk(RecordFormatUtils.FRAME_KIND, payloadBytes);
        this.quality.commit(this.stagedFrame, this.stagedElapsedNanoseconds, this.stagedConsistency, this.stagedUnavailableSamples);
        this.activeFrame = false;
    }

    /**
     * Discards the current frame stage, including a failed append.
     * <p>
     * This operation does not repair a previous file output failure.
     *
     * @throws IllegalStateException if the writer is closed
     */
    public void abortFrame() {
        if (this.closed) {
            throw Exceptions.newIllegalStateException("The record writer is closed");
        }

        this.activeFrame    = false;
        this.stageFaulted   = false;
        this.stagedBytes    = 0;
        this.stagedSections = 0;
    }

    /**
     * Writes the final quality summary and flushes output.
     *
     * @param termination the actual session termination
     * @return the validated final summary
     * @throws IOException           if summary encoding, finalization, or flushing fails
     * @throws IllegalStateException if the writer is closed or finalized, or a frame is still staged
     */
    public @NotNull RecordSummary finish(@NotNull Termination termination) throws IOException {
        this.requireWritable();

        if (this.activeFrame) {
            throw Exceptions.newIllegalStateException("Commit or abort the current frame before finalizing");
        }

        RecordSummary finalSummary = this.quality.summarize(termination);
        int payloadBytes = RecordFormatUtils.SUMMARY_PREFIX_BYTES + finalSummary.getMissingRanges().size() * RecordFormatUtils.MISSING_RANGE_BYTES;

        this.committed = RecordFormatUtils.growBuffer(this.committed, payloadBytes);

        ByteBuffer payload = ByteBuffer.wrap(this.committed).order(ByteOrder.LITTLE_ENDIAN);

        payload.putLong(finalSummary.getFrameCount()).putLong(finalSummary.getFirstFrame()).putLong(finalSummary.getLastFrame());
        payload.putInt(termination.getCode())
               .putInt(finalSummary.isRequirementsSatisfied() ? RecordFormatUtils.SUMMARY_REQUIREMENTS_SATISFIED_FLAG : 0);
        payload.putLong(finalSummary.getMissingFrameCount()).putLong(finalSummary.getUnavailableSampleCount())
               .putLong(finalSummary.getTornObservationCount());
        payload.putInt(finalSummary.getMissingRanges().size()).putInt(0);

        for (FrameRange range : finalSummary.getMissingRanges()) {
            payload.putLong(range.getFirstFrame()).putLong(range.getLastFrame());
        }

        this.writeChunk(RecordFormatUtils.END_KIND, payloadBytes);

        try {
            this.output.flush();
        } catch (IOException | RuntimeException exception) {
            this.outputFaulted = true;

            throw exception;
        }

        this.summary  = finalSummary;
        this.finished = true;

        return finalSummary;
    }

    private int findSectionIndex(@NotNull SampleDefinition<?> definition) throws IOException {
        List<SampleDefinition<?>> definitions = this.record.getDefinitions();

        for (int sectionIndex = 0; sectionIndex < definitions.size(); sectionIndex++) {
            if (definitions.get(sectionIndex) == definition) {
                if (this.sectionOffsets[sectionIndex] >= 0) {
                    throw Exceptions.newIOException("Duplicate sample observation: {0}", definition.getName());
                }

                return sectionIndex;
            }
        }

        throw Exceptions.newIOException("Sample definition was not selected: {0}", definition.getName());
    }

    private void requireWritable() throws IOException {
        if (this.closed || this.finished) {
            throw Exceptions.newIllegalStateException("The record writer is closed or finalized");
        }

        if (this.outputFaulted) {
            throw Exceptions.newIOException("The record writer is faulted after an output failure");
        }
    }

    private void requireStage() throws IOException {
        this.requireWritable();

        if (!this.activeFrame || this.stageFaulted) {
            throw Exceptions.newIllegalStateException("Begin a new frame or abort the failed stage");
        }
    }

    private void writeChunk(short kind, int payloadBytes) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(this.chunkHeader).order(ByteOrder.LITTLE_ENDIAN);

        header.putShort(kind).putShort((short) 0).putInt(payloadBytes).putLong(this.sequence).putInt(0).putInt(0);

        header.putInt(RecordFormatUtils.CHUNK_CHECKSUM_OFFSET,
                      RecordFormatUtils.calculateChunkChecksum(this.checksum, this.chunkHeader, this.committed, payloadBytes));

        try {
            this.output.write(this.chunkHeader);
            this.output.write(this.committed, 0, payloadBytes);
            this.sequence++;
        } catch (IOException | RuntimeException exception) {
            this.outputFaulted = true;

            throw exception;
        }
    }


    /**
     * Closes this stream and releases any system resources associated
     * with it. If the stream is already closed then invoking this
     * method has no effect.
     *
     * <p>
     * As noted in {@link AutoCloseable#close()}, cases where the
     * close may fail require careful attention. It is strongly advised
     * to relinquish the underlying resources and to internally
     * <em>mark</em> the {@code Closeable} as closed, prior to throwing
     * the {@code IOException}.
     *
     * @throws IOException if an I/O error occurs
     * @implSpec Closes the owned output stream without fabricating an {@code END} chunk; the writer is marked closed and its
     * frame stage inactive first.
     */
    @Override
    public void close() throws IOException {
        if (!this.closed) {
            this.closed      = true;
            this.activeFrame = false;
            this.output.close();
        }
    }
}
