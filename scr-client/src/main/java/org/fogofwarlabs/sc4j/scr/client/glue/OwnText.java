package org.fogofwarlabs.sc4j.scr.client.glue;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Lists SC4J's own texts of the glue screens, in English and Korean: the main menu's status panel and the Beacon.Link screens. The install
 * and the engine's {@code CommunityText} have no texts for them. A text with {@code %s} or {@code %d} takes its values through
 * {@link #format}.
 */
public enum OwnText {
    VERSION("Version", "버전"),
    LATEST_RELEASE("Latest release", "최신 릴리즈"),
    UPDATE("Update", "업데이트"),
    BEACON_LINK("Beacon.Link", "Beacon.Link"),

    CHECKING("Checking", "확인 중"),
    UNKNOWN("Unknown", "알 수 없음"),
    UPDATE_AVAILABLE("Available", "있음"),
    UP_TO_DATE("Up to date", "최신 버전"),

    ONLINE("Online", "온라인"),
    CONNECTING("Connecting", "연결 중"),
    READY("Ready", "준비됨"),
    OFFLINE("Offline", "오프라인"),

    CONNECTION_REQUIREMENTS("Requirements: Your computer will need access to the Internet and an SC4J account.",
                            "요구 조건: 컴퓨터가 인터넷에 연결되어 있고 SC4J 계정이 있어야 합니다."),

    MOD("Mod", "모드"),
    NO_NODES("Not set up", "설정 없음"),
    NO_ANSWER("No answer", "응답 없음"),
    REFUSED("Refused", "거부됨"),
    TOO_MANY_DEVICES("Too many devices", "기기가 너무 많음"),
    SIGNED_OUT("Signed out", "로그아웃됨"),

    WHISPER_BUTTON("wWhisper", "w귓속말 (W)"),
    LADDER_BUTTON("lLadder", "l래더 (L)"),
    RECORDS_BUTTON("rRecords", "r기록 (R)"),
    FRIEND_LABEL("Name#tag", "이름#태그"),

    SHOW_CLOSED_GAMES("Show closed games", "닫힌 게임 표시"),
    GAME_NAME_DEFAULT("%s's game", "%s의 게임");

    /**
     * The install locale whose texts are Korean.
     */
    private static final @NotNull String KOREAN_LOCALE = "koKR";

    private final @NotNull String english;
    private final @NotNull String korean;

    OwnText(@NotNull String english, @NotNull String korean) {
        this.english = english;
        this.korean  = korean;
    }

    /**
     * Returns the text of a locale.
     *
     * @param locale the install locale, for example {@code koKR}
     * @return the Korean text for {@code koKR}, the English text otherwise
     */
    public @NotNull String get(@NotNull String locale) {
        return KOREAN_LOCALE.equals(locale) ? this.korean : this.english;
    }

    /**
     * Returns the text of a locale with its values.
     *
     * @param locale the install locale, for example {@code koKR}
     * @param values the values of the text's {@code %s} and {@code %d}
     * @return the formatted text
     */
    public @NotNull String format(@NotNull String locale, @NotNull Object @NotNull ... values) {
        return String.format(Locale.ROOT, this.get(locale), values);
    }
}
