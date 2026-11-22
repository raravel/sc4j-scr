package org.fogofwarlabs.sc4j.scr;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.mod.*;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.scr.client.Main;
import org.fogofwarlabs.sc4j.scr.client.configuration.InstallLayout;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.casc.BuildInfo;
import org.fogofwarlabs.sc4j.scr.data.format.casc.GameLocaleUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.game.replay.ScrReplayFormat;
import org.fogofwarlabs.sc4j.scr.game.save.SaveGameKind;
import org.fogofwarlabs.sc4j.scr.game.save.ScrSaveFormat;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.load.ScrIscriptImporter;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Enters the StarCraft: Remastered mod: it checks the user's StarCraft: Remastered installation, the folder of its setting
 * {@value #GAME_FOLDER_SETTING}, and refuses every build other than {@value #SUPPORTED_BUILD}; then it registers the
 * SC:R client as the mod's application, the installation's resources ({@link ScrResourceProvider}), the SC:R simulation
 * ({@link Game#register}): its random generator ({@link LcgRandom}), its pools and its game system, and its formats: the native replay
 * format ({@link ScrReplayFormat}), in which the runtime finds a {@code --replay} file, and the native saved games of every kind
 * ({@link ScrSaveFormat}).
 * <p>
 * The client runs with the mod's storage folder as its root, so its in-game options ({@code sc4j-settings.json}), maps
 * (the only folder the map list shows), replays, desync dumps ({@code desyncs}), saved games, single player IDs ({@code characters}) and
 * caches live in {@code <install>/userdata/mods/scr/}, while the log is the runtime's. The installation's build version is added to the
 * mod identity. The text language is the setting {@value #LOCALE_SETTING} when the installation has it, English otherwise.
 */
public final class ScrMod implements Mod {
    /**
     * The setting naming the StarCraft: Remastered folder, which every mod that declares its shared name uses.
     */
    public static final @NotNull String GAME_FOLDER_SETTING = "gameFolder";

    /**
     * The setting naming the text language, such as {@code koKR}.
     */
    public static final @NotNull String LOCALE_SETTING = "locale";

    /**
     * The only StarCraft: Remastered build the mod runs with.
     */
    public static final @NotNull String SUPPORTED_BUILD = "1.23.10.13515";

    private static final @NotNull String BUILD_INFO_FILE = ".build.info";
    private static final @NotNull String DATA_OPTION = "--data";
    private static final @NotNull String LOCALE_OPTION = "--locale";
    private static final @NotNull String REPLAY_OPTION = "--replay";

    /**
     * Runs the SC:R client as the mod's application with its own loop.
     */
    private static final class ClientApplication implements ModApplication {
        private final @NotNull Path clientRoot;
        private final @NotNull List<@NotNull String> clientArguments;
        private final @NotNull ScrResourceProvider resources;

        /**
         * Creates a {@link ClientApplication} instance.
         *
         * @param clientRoot      the client root, the mod's storage folder
         * @param clientArguments the client launch options derived from the settings, before the run's own options
         * @param resources       the registered resource provider, which the client reads
         */
        private ClientApplication(@NotNull Path clientRoot, @NotNull List<@NotNull String> clientArguments, @NotNull ScrResourceProvider resources) {
            this.clientRoot      = clientRoot;
            this.clientArguments = List.copyOf(clientArguments);
            this.resources       = resources;
        }

        /**
         * @return {@code true} if the application can run without a window, audio and input ({@code --headless}),
         * {@code false} otherwise
         */
        @Override
        public boolean isHeadlessCapable() {
            return false;
        }

        /**
         * Runs the application until the user or the launch request ends it.
         *
         * @param launch the launch request of the command line and the mod's identity
         * @throws ModException if the application refuses the launch request
         */
        @Override
        public void run(@NotNull ModLaunch launch) throws ModException {
            if (launch.getJoin() != null) {
                throw ModException.create("The SC:R mod cannot join {0} from the command line; join a LAN or Beacon.Link game from the " +
                                          "Multiplayer menu.", launch.getJoin());
            }

            List<String> arguments = new ArrayList<>(this.clientArguments);
            Path replay = launch.getReplay();

            if (replay != null) {
                arguments.add(REPLAY_OPTION);
                arguments.add(replay.toString());
            }

            try {
                Main.launch(InstallLayout.of(this.clientRoot), this.resources, launch.getBeaconLink(),
                            describeBuild(launch.getIdentity()), arguments.toArray(String[]::new));
            } catch (IOException exception) {
                throw Exceptions.newUncheckedIOException(exception);
            }
        }
    }


    /**
     * Describes the run's identity as the runtime's {@code IDENTITY} line does, with the full content and data digests.
     *
     * @param identity the run's identity
     * @return the description
     */
    private static @NotNull String describeBuild(@NotNull ModIdentity identity) {
        return identity.getModId() + " " + identity.getModVersion() + " (engine " + identity.getEngine() +
               ", content " + HexFormat.of().formatHex(identity.getContentDigest()) +
               ", data " + HexFormat.of().formatHex(identity.getDataDigest()) + ")";
    }


    private @Nullable ModContext context;
    private @Nullable Path installation;
    private @Nullable String installedBuild;
    private @Nullable ScrResourceProvider resources;

    /**
     * Creates a {@link ScrMod} instance; the runtime calls {@link #initialize} next.
     */
    public ScrMod() {
        this.context        = null;
        this.installation   = null;
        this.installedBuild = null;
        this.resources      = null;
    }

    /**
     * Opens and validates what the mod needs, such as external data and its version.
     *
     * @param context the settings, storage, logger and paths of the run
     * @throws ModException if the mod refuses the run, for example for unsupported external data
     * @implSpec Reads the {@code .build.info} of the installation and refuses every build other than {@value #SUPPORTED_BUILD}.
     */
    @Override
    public void initialize(@NotNull ModContext context) throws ModException {
        ModSettings settings = context.getSettings();

        if (!settings.hasValue(GAME_FOLDER_SETTING)) {
            throw ModException.create("The mod {0} has no StarCraft: Remastered folder (setting {1}).", context.getManifest().getId(), GAME_FOLDER_SETTING);
        }

        Path installation = settings.getDirectory(GAME_FOLDER_SETTING);
        Path buildInfoFile = installation.resolve(BUILD_INFO_FILE);
        BuildInfo buildInfo;

        try {
            buildInfo = BuildInfo.readFrom(buildInfoFile);
        } catch (IOException | IllegalArgumentException exception) {
            throw ModException.create(exception, "The StarCraft: Remastered installation {0} has no readable {1}: {2}", installation, BUILD_INFO_FILE,
                                      exception.getMessage());
        }

        if (!SUPPORTED_BUILD.equals(buildInfo.getVersion())) {
            throw ModException.create("StarCraft: Remastered {0} in {1} is not supported; the SC:R mod runs with build {2} only.", buildInfo.getVersion(),
                                      installation, SUPPORTED_BUILD);
        }

        context.getLogger().info("SC:R INSTALLATION: {0} (build {1})", installation, buildInfo.getVersion());

        this.context        = context;
        this.installation   = installation;
        this.installedBuild = buildInfo.getVersion();
    }

    /**
     * Registers what the mod adds to the engine. The registration is closed when this method returns.
     *
     * @param registration receives the mod's registrations
     * @throws ModException if the mod cannot register what it needs
     * @implSpec Adds the installation's build version to the identity, registers the installation's resource provider (it opens the storage
     * on the first load), the client application, the SC:R simulation, the native replay format and the native save format of each
     * {@link SaveGameKind}.
     */
    @Override
    public void register(@NotNull ModRegistration registration) throws ModException {
        ModContext initialized = this.context;
        Path game = this.installation;
        String build = this.installedBuild;

        if (initialized == null || game == null || build == null) {
            throw ModException.create("The SC:R mod registers only after a successful initialize.");
        }

        ModSettings settings = initialized.getSettings();
        ScrResourceProvider provider = ScrResourceProvider.open(game, ScrIscriptImporter::readLibrary);
        String requestedLocale = settings.hasValue(LOCALE_SETTING) ? settings.getChoice(LOCALE_SETTING) : GameLocaleUtils.FALLBACK;
        String locale = GameLocaleUtils.selectInstalled(requestedLocale, game);
        List<String> clientArguments = new ArrayList<>(List.of(DATA_OPTION, game.toString(), LOCALE_OPTION, locale));

        if (!locale.equals(requestedLocale)) {
            initialized.getLogger().info("SC:R LOCALE: {0} is not installed in {1}; using {2}.", requestedLocale, game, locale);
        }

        clientArguments.addAll(initialized.getArguments());

        this.resources = provider;

        registration.addIdentityData(build.getBytes(StandardCharsets.UTF_8));
        registration.registerResourceProvider(provider);
        registration.registerApplication(new ClientApplication(initialized.getStorage().getRoot(), clientArguments, provider));
        Game.register(registration.getSimulation());
        registration.registerReplayFormat(identity -> new ScrReplayFormat());

        for (SaveGameKind kind : SaveGameKind.values()) {
            registration.registerSaveFormat(identity -> new ScrSaveFormat(kind));
        }
    }

    /**
     * Releases what {@link #initialize} opened, in reverse order.
     *
     * @implSpec The mod opens nothing in {@link #initialize}; the client application releases its own resources when it returns, and the
     * resource provider (and its storage, if opened) is closed here.
     */
    @Override
    public void close() {
        ScrResourceProvider provider = this.resources;

        if (provider != null) {
            this.resources = null;
            provider.close();
        }

        this.installedBuild = null;
        this.installation   = null;
        this.context        = null;
    }
}
