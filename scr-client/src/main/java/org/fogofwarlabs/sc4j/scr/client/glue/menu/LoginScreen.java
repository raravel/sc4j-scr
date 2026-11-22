package org.fogofwarlabs.sc4j.scr.client.glue.menu;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.text.AsciiCaseUtils;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.scr.client.ClientPlayer;
import org.fogofwarlabs.sc4j.scr.client.glue.*;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueEditPopup;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueSavePopup;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ListBox;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ScrControl;
import org.fogofwarlabs.sc4j.scr.core.FileNameUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lets a run without an account choose its local ID: the list of the local IDs of the local ID folder ({@link CharacterFile#list},
 * {@link GlueContext#getLocalIdDirectory()}), oldest first with the first one selected, and New ID and Delete.
 * <p>
 * Ok, Enter or a double press on the list makes the selected ID the run's player ({@link GlueContext#setCharacter}) and shows the next
 * destination; Cancel shows the main menu. Ok and Delete are enabled while an ID is selected. Without any ID, New ID opens by itself once
 * the panels are in.
 * <p>
 * New ID asks for a name in the edit popup, which refuses the localized bad file name characters. A full list, a name that is no valid
 * file name, a name another ID has (compared ignoring ASCII case) and a file that cannot be written are each refused with their message;
 * a created ID is listed by its creation time and selected. Delete asks for confirmation, then deletes the ID's file and its saved games
 * ({@link CharacterFile#delete}) and keeps the selection on the same row, or the last one.
 */
public final class LoginScreen extends GlueScreen {
    private static final @NotNull Logger LOGGER = Logger.of(LoginScreen.class);

    private static final @NotNull String DIALOG = "gluLogin";

    private static final int OK = 4;
    private static final int CANCEL = 5;
    private static final int NEW_ID = 6;
    private static final int DELETE = 7;
    private static final int LIST = 8;
    /**
     * The slide-in panels: the list panel {@code 1} from the left, the Cancel panel {@code 2} from below and the Ok panel {@code 3} from the
     * right, no delay.
     */
    private static final int @NotNull [] SWISH = {1, 0, 2, 3, 3, 2};
    private static final int SWISH_DELAY_MILLISECONDS = 0;
    /**
     * The time after the screen opened at which an empty list opens New ID.
     */
    private static final int NEW_ID_DELAY_MILLISECONDS = 50;
    private static final int FIRST_ROW = 0;

    private static final @NotNull String NEW_NAME_KEY = "loginNEWCHAR";
    private static final @NotNull String TOO_MANY_KEY = "loginTOOMANY";
    private static final @NotNull String NAME_EXISTS_KEY = "loginNAMEEXISTS";
    private static final @NotNull String CREATE_ERROR_KEY = "loginCREATEERROR";
    private static final @NotNull String CONFIRM_DELETE_KEY = "loginDELCHAR";
    private static final @NotNull String DELETE_ERROR_KEY = "loginDELCHARFAIL";

    private final GlueNavigator.@NotNull Destination next;

    private @Nullable ListBox list;
    private @NotNull List<CharacterFile> characters;
    private boolean newIdPending;

    private @Nullable ScrControl okButton;
    private @Nullable ScrControl deleteButton;

    /**
     * Creates a {@link LoginScreen} instance.
     *
     * @param next the destination Ok shows after the ID became the run's player
     */
    public LoginScreen(GlueNavigator.@NotNull Destination next) {
        super(DIALOG, SHARED_PALETTE_FOLDER, getPaletteBackground(SHARED_PALETTE_FOLDER));

        this.next = next;

        this.list         = null;
        this.characters   = List.of();
        this.newIdPending = false;

        this.okButton     = null;
        this.deleteButton = null;
    }

    /**
     * @return the selected ID, or {@code null} without a selection
     */
    private @Nullable CharacterFile getSelectedCharacter() {
        ListBox names = this.list;
        int selection = names == null ? ListBox.NO_SELECTION : names.getSelection();

        return selection < 0 || selection >= this.characters.size() ? null : this.characters.get(selection);
    }


    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Lists the local IDs with the first one selected; an empty list opens New ID once the panels are in.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        this.okButton     = this.bindControl(OK);
        this.deleteButton = this.bindControl(DELETE);

        this.setSwish(SWISH, SWISH_DELAY_MILLISECONDS);

        ListBox names = this.addListBox(LIST);

        // The list shows plain text over the panel art without row bars.
        names.setTransparent(true);
        this.list = names;

        this.fillCharacters(null);
        this.newIdPending = this.characters.isEmpty();
    }

    /**
     * Lists the local IDs of the character folder and selects one.
     *
     * @param fileName the file name of the ID to select, or {@code null} for the first one
     */
    private void fillCharacters(@Nullable String fileName) {
        ListBox names = requireBound(this.list);
        List<String> texts = new ArrayList<>();

        this.characters = CharacterFile.list(this.getContext().getLocalIdDirectory());

        int selection = this.characters.isEmpty() ? ListBox.NO_SELECTION : FIRST_ROW;

        for (int index = 0; index < this.characters.size(); index++) {
            CharacterFile character = this.characters.get(index);

            texts.add(character.getName());

            if (character.getFileName().equals(fileName)) {
                selection = index;
            }
        }

        names.setItems(texts);
        this.select(selection);
    }

    private void select(int index) {
        ListBox names = requireBound(this.list);

        names.select(index, names.getVisibleRows(this.getContext().getCanvas()));
        this.updateActions();
    }

    /**
     * Enables Ok and Delete while an ID is selected.
     */
    private void updateActions() {
        boolean selected = this.getSelectedCharacter() != null;

        requireBound(this.okButton).setEnabled(selected);
        requireBound(this.deleteButton).setEnabled(selected);
    }

    /**
     * Called by {@link #update} for screen-specific timers.
     * <p>
     * Opens New ID for an empty list once the screen takes input and the delay passed.
     *
     * @param time the time in milliseconds
     */
    @Override
    protected void onUpdate(long time) {
        if (this.newIdPending && this.isInteractive() && this.getElapsed() >= NEW_ID_DELAY_MILLISECONDS) {
            this.newIdPending = false;
            this.askNewName();
        }
    }

    /**
     * Called after the user changed the selection of a list or combo box, or the value of a slider.
     * <p>
     * Updates Ok and Delete after the ID selection changed.
     *
     * @param control the control id
     */
    @Override
    protected void onSelectionChanged(int control) {
        if (control == LIST) {
            this.updateActions();
        }
    }

    /**
     * Called when a control is activated by a click, its hotkey, Esc or Enter.
     * <p>
     * Ok plays as the selected ID, Cancel shows the main menu, New ID asks for a name and Delete asks to delete the selected ID.
     *
     * @param control the control id
     */
    @Override
    protected void onActivate(int control) {
        switch (control) {
            case OK -> this.choose();
            case CANCEL -> this.leave(() -> this.getContext().getNavigator().show(GlueNavigator.Destination.MAIN_MENU));
            case NEW_ID -> this.askNewName();
            case DELETE -> this.confirmDelete();
            default -> {
            }
        }
    }

    /**
     * Makes the selected ID the run's player and shows the next destination.
     */
    private void choose() {
        CharacterFile character = this.getSelectedCharacter();

        if (character == null) {
            return;
        }

        GlueContext glueContext = this.getContext();

        glueContext.setCharacter(character);
        LOGGER.info("LOGIN: {0}", character.getName());
        this.leave(() -> glueContext.getNavigator().show(this.next));
    }

    /**
     * Asks for the name of a new ID, or tells that the list is full.
     */
    private void askNewName() {
        GlueContext glueContext = this.getContext();

        if (this.characters.size() >= CharacterFile.MAXIMUM_COUNT) {
            this.showMessage(glueContext.getGluText().get(TOO_MANY_KEY));

            return;
        }

        this.showPopup(new GlueEditPopup(this.getPaletteFolder(),
                                         glueContext.getGluText().get(NEW_NAME_KEY), CharacterFile.MAXIMUM_NAME_LENGTH,
                                         glueContext.getNetworkText().get(FileNameUtils.BAD_CHARACTERS_KEY),
                                         this::createCharacter));
    }

    /**
     * Creates an ID of a typed name and selects it, or tells why the name is refused.
     *
     * @param text the typed name, or {@code null} if New ID was canceled
     */
    private void createCharacter(@Nullable String text) {
        if (text == null) {
            return;
        }

        GlueContext glueContext = this.getContext();
        byte[] name = TerminatedStringUtils.cutText(text, CharacterFile.NAME_SIZE);
        String badCharacters = glueContext.getNetworkText().get(FileNameUtils.BAD_CHARACTERS_KEY);

        if (!FileNameUtils.isValidFileName(name, CharacterFile.NAME_SIZE, badCharacters)) {
            this.showMessage(glueContext.getNetworkText().get(GlueSavePopup.RESERVED_FILE_NAME_KEY));

            return;
        }

        String cutName = TerminatedStringUtils.readText(name);

        for (CharacterFile character : this.characters) {
            if (AsciiCaseUtils.equalsIgnoreCase(character.getName(), cutName)) {
                this.showMessage(glueContext.getGluText().get(NAME_EXISTS_KEY));

                return;
            }
        }

        CharacterFile created;

        try {
            created = CharacterFile.createNamed(glueContext.getLocalIdDirectory(), name);
        } catch (IOException exception) {
            LOGGER.warning("CHARACTER CREATE FAILED: {0}: {1}", cutName, exception.getMessage());
            this.showMessage(glueContext.getGluText().get(CREATE_ERROR_KEY));

            return;
        }

        this.fillCharacters(created.getFileName());
    }

    /**
     * Asks to delete the selected ID and its saved games.
     */
    private void confirmDelete() {
        CharacterFile character = this.getSelectedCharacter();

        if (character == null) {
            return;
        }

        this.showPopup(new GlueMessagePopup(GlueMessagePopup.Kind.OK_CANCEL,
                                            this.getPaletteFolder(),
                                            this.getContext().getGluText().get(CONFIRM_DELETE_KEY),
                                            null,
                                            confirmed -> {
                                                if (confirmed) {
                                                    this.deleteCharacter(character);
                                                }
                                            }));
    }

    /**
     * Deletes an ID's file and its saved games and keeps the selection on the same row, or the last one.
     *
     * @param character the ID
     */
    private void deleteCharacter(@NotNull CharacterFile character) {
        GlueContext glueContext = this.getContext();
        ClientPlayer player = ClientPlayer.ofLocalId(character.getName(), character.getFileName());

        try {
            character.delete(glueContext.getFolders().getSaveDirectory(player));
        } catch (IOException exception) {
            LOGGER.warning("CHARACTER DELETE FAILED: {0}: {1}", character.getName(), exception.getMessage());
            this.showMessage(glueContext.getGluText().get(DELETE_ERROR_KEY));

            return;
        }

        int index = this.characters.indexOf(character);
        List<CharacterFile> kept = new ArrayList<>(this.characters);
        ListBox names = requireBound(this.list);

        kept.remove(index);
        this.characters = kept;
        names.removeItem(index);
        this.select(names.getSelection());
    }

    /**
     * Shows a message with an Ok button.
     *
     * @param text the message
     */
    private void showMessage(@NotNull String text) {
        this.showPopup(new GlueMessagePopup(GlueMessagePopup.Kind.OK, this.getPaletteFolder(), text, null, ok -> {
        }));
    }
}
