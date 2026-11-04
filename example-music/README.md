# Примеры треков

Положите эти файлы в `config/custommusic/music/` и выполните `/customdisc reload`.
Файлы намеренно названы «неудобно» (пробелы, скобки, кириллица), чтобы показать
превращение имён в безопасные `track_id`:

| Файл | `track_id` | Что демонстрирует |
|---|---|---|
| `Example - Chiptune Menu Theme.wav` | `example_chiptune_menu_theme` | `.wav` → конвертация в mono Ogg |
| `Track-01 final (stereo).wav` | `track-01_final_stereo` | стерео → downmix в моно |
| `Night Owl (Demo).ogg` | `night_owl_demo` | готовый mono 44.1 kHz Ogg (passthrough) |

Все файлы сгенерированы программно (синтез), маленькие и без лицензионных ограничений.
