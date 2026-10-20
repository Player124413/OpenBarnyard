package org.opentoshi.openbarnyard.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Commands the player can bind to touch controls. The numeric ids are the
 * engine's own {@code AInputCommand} values from
 * {@code OpenBarnyard/Source/Input/AInputMap.h} — the touch layer speaks the
 * game's native language, it does not invent a parallel scheme.
 * Keep in sync with AInputMap.h and cpp/core/InputState.h.
 */
public enum GameCommand {
    OK          (1,  "OK",        "OK",           "ОК"),
    CANCEL      (2,  "Cancel",    "✕",            "Отмена"),
    BACK        (4,  "Back",      "←",            "Назад"),
    START       (7,  "Start",     "▶",            "Старт"),
    SELECT      (8,  "Select",    "SEL",          "Выбор"),
    RESTART     (12, "Restart",   "↺",            "Заново"),
    FORWARD     (22, "Forward",   "▲",            "Вперёд"),
    BACKWARD    (26, "Backward",  "▼",            "Назад (ход)"),
    ZOOM_IN     (30, "Zoom in",   "+",            "Зум +"),
    ZOOM_OUT    (31, "Zoom out",  "−",            "Зум −"),
    LOOK        (32, "Look",      "👁",           "Осмотр"),
    CONTEXT1    (41, "Context 1", "C1",           "Действие 1"),
    CONTEXT2    (42, "Context 2", "C2",           "Действие 2"),
    CONTEXT3    (43, "Context 3", "C3",           "Действие 3"),
    CONTEXT4    (44, "Context 4", "C4",           "Действие 4"),
    MENU        (51, "Menu",      "≡",            "Меню"),
    INTERACT    (53, "Interact",  "E",            "Взаимодействие"),
    USE         (54, "Use",       "USE",          "Использовать"),
    SHOOT       (55, "Shoot",     "●",            "Бросок/выстрел"),
    SHOOT_ALT   (56, "Shoot alt", "○",            "Альт. бросок"),
    JUMP        (57, "Jump",      "A",            "Прыжок"),
    SPEED       (58, "Speed/Run", "B",            "Бег/скорость"),
    BRAKE       (59, "Brake",     "BRK",          "Тормоз"),
    ACCELERATOR (60, "Accelerate","GAS",          "Газ"),
    BELL        (61, "Bell",      "🔔",           "Звонок"),
    TRICK1      (62, "Trick 1",   "T1",           "Трюк 1"),
    TRICK2      (63, "Trick 2",   "T2",           "Трюк 2"),
    SIDESTEP    (66, "Side step", "⇄",            "Шаг вбок"),
    SWITCH      (67, "Switch",    "SW",           "Смена"),
    RELOAD      (68, "Reload",    "R",            "Перезарядка"),
    DODGE       (69, "Dodge",     "DGE",          "Уклонение"),
    INVENTORY   (75, "Inventory", "🎒",           "Инвентарь"),
    ICONS       (76, "Icons/Map", "MAP",          "Карта/значки"),
    ZLOCK       (78, "Z-Lock",    "◎",            "Захват цели"),
    HORN        (80, "Horn",      "📯",           "Гудок");

    public final int id;
    /** English label (also the fallback). */
    public final String labelEn;
    /** Very short label drawn inside the touch button. */
    public final String shortLabel;
    public final String labelRu;

    GameCommand(int id, String labelEn, String shortLabel, String labelRu) {
        this.id = id;
        this.labelEn = labelEn;
        this.shortLabel = shortLabel;
        this.labelRu = labelRu;
    }

    public String label(boolean russian) {
        return russian ? labelRu : labelEn;
    }

    public static GameCommand byId(int id) {
        for (GameCommand c : values()) if (c.id == id) return c;
        return null;
    }

    /** Commands worth offering in the editor's binding picker, in UI order. */
    public static List<GameCommand> bindable() {
        List<GameCommand> list = new ArrayList<GameCommand>();
        GameCommand[] order = {
            JUMP, INTERACT, USE, SPEED, CONTEXT1, CONTEXT2, CONTEXT3, CONTEXT4,
            SHOOT, SHOOT_ALT, MENU, INVENTORY, ICONS, OK, CANCEL, BACK, START,
            SELECT, ZOOM_IN, ZOOM_OUT, LOOK, ZLOCK, DODGE, SIDESTEP, SWITCH,
            RELOAD, ACCELERATOR, BRAKE, BELL, HORN, TRICK1, TRICK2, RESTART,
            FORWARD, BACKWARD
        };
        for (GameCommand c : order) list.add(c);
        return list;
    }
}
