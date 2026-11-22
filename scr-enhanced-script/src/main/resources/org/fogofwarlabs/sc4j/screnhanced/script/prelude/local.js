// The local information of the upper layer: the global local. What only the local machine knows differs from peer to peer, so it is readable
// only in local code (onLocalFrame, onRender and the local input hooks); elsewhere every function throws a TypeError. The player is null in a
// replay and for an observer; the screen and pointer positions are null while the client has not supplied them.
(function (core, prelude) {
    'use strict';

    var requireObject = prelude.requireObject;
    var positionPrototype = prelude.positionPrototype;
    var createPosition = prelude.createPosition;
    var getPlayer = prelude.getPlayer;
    var toUnits = prelude.toUnits;
    var ButtonState = prelude.ButtonState;
    var buttonStatePrototype = prelude.buttonStatePrototype;
    var getButtonSet = prelude.getButtonSet;
    var readButtonSetId = prelude.readButtonSetId;

    // The displayed set of a card that shows none.
    var NO_BUTTON_SET = 0xE4;

    var local = Object.freeze({
                                  getPlayer: function () {
                                      return getPlayer(core.local.getPlayer());
                                  },
                                  isReplay: function () {
                                      return core.local.isReplay();
                                  },
                                  isChatOpen: function () {
                                      return core.local.isChatOpen();
                                  },
                                  // The map position of the local screen's top left.
                                  getScreenPosition: function () {
                                      return core.local.isViewKnown() ? createPosition(core.local.getScreenX(), core.local.getScreenY()) : null;
                                  },
                                  // Moves only the local screen's top left to a map position at once, kept inside the map; false while the screen is
                                  // unknown.
                                  setScreenPosition: function (position) {
                                      requireObject(position, positionPrototype, 'A position');

                                      return core.local.setScreenPosition(position.x, position.y);
                                  },
                                  // The map position under the pointer.
                                  getMousePosition: function () {
                                      return core.local.isViewKnown() ? createPosition(core.local.getMouseX(), core.local.getMouseY()) : null;
                                  },
                                  // The pointer in interface units {x, y}, the units of the screen drawing, at any interface size and zoom.
                                  getPointerPosition: function () {
                                      return core.local.isPointerKnown() ? Object.freeze({
                                                                                             x: core.local.getPointerX(),
                                                                                             y: core.local.getPointerY()
                                                                                         }) : null;
                                  },
                                  // The size of the local interface in interface units: as wide as the interface, 480 high.
                                  getInterfaceWidth: function () {
                                      return core.screen.getWidth();
                                  },
                                  getInterfaceHeight: function () {
                                      return core.screen.getHeight();
                                  },
                                  // The units of the local interface's selection, which may differ from the local player's synchronized selection
                                  // until its selection command runs.
                                  getSelection: function () {
                                      return toUnits(core.local.getSelection());
                                  },
                                  // The button set the local command card shows, null for none.
                                  getDisplayedButtonSet: function () {
                                      var set = core.local.getDisplayedButtonSet();

                                      return set === NO_BUTTON_SET ? null : getButtonSet(set);
                                  },
                                  // Shows a button set on the local command card at its next refresh, as the Change Buttons action does, until the
                                  // selection changes.
                                  changeButtons: function (set) {
                                      core.local.changeButtons(readButtonSetId(set));
                                  },
                                  // The local state of a condition value of the Script button condition; every state starts Hidden, the native zero
                                  // value.
                                  getButtonState: function (conditionValue) {
                                      return ButtonState.get(core.local.getButtonState(conditionValue));
                                  },
                                  setButtonState: function (conditionValue, state) {
                                      core.local.setButtonState(conditionValue, requireObject(state, buttonStatePrototype, 'A button state').id);
                                  },
                                  // Pauses and resumes the game as the game menu does, with the local player's Pause and Resume commands, which run
                                  // in a later turn on every peer; a network pause takes one of the player's pauses left. Nothing in a replay.
                                  requestPause: function () {
                                      core.local.requestPause();
                                  },
                                  requestResume: function () {
                                      core.local.requestResume();
                                  }
                              });

    return {
        exports: {
            local: local
        },
        shared: {}
    };
});
