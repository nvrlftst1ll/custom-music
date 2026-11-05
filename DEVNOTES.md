# DEVNOTES — заметки для сопровождения

Проект собран и проверен компиляцией против **NeoForge 21.1.77 / Minecraft 1.21.1**
(Mojang mappings, Gradle 8.10, NeoGradle 7.0.171). Ниже — проверенные факты об API
и месте, чувствительные к обновлениям.

## Проверенные сигнатуры NeoForge 21.1.77 (важно при обновлениях)

| # | API | Фактическая сигнатура (проверено компиляцией) |
|---|-----|-----------------------------------------------|
| 1 | `SoundInstance` | `net.minecraft.client.resources.sounds.SoundInstance` (НЕ `client.sounds`; а вот `SoundEngine` — `net.minecraft.client.sounds.SoundEngine`) |
| 2 | `Pack` | `net.minecraft.server.packs.repository.Pack`; `Pack.readMetaAndCreate(PackLocationInfo, Pack.ResourcesSupplier, PackType, PackSelectionConfig)` — 4 аргумента |
| 3 | `Pack.ResourcesSupplier` | НЕ функциональный интерфейс: `openPrimary(PackLocationInfo)` + `openFull(PackLocationInfo, Pack.Metadata)` — нужен анонимный класс |
| 4 | `PackLocationInfo` | `record PackLocationInfo(String id, Component title, PackSource source, Optional<KnownPack> knownPackInfo)` |
| 5 | `PackSelectionConfig` | `record PackSelectionConfig(boolean required, Pack.Position defaultPosition, boolean fixedPosition)` |
| 6 | `PackResources` | методы: `packId()`, `location(): PackLocationInfo` (абстрактный!), `getRootResource(String...)`, `getResource(PackType, ResourceLocation)`, `listResources(PackType, String, String, ResourceOutput)`, `getNamespaces(PackType)`, `getMetadataSection(MetadataSectionSerializer<T>)` — именно `MetadataSectionSerializer` (с методом `getMetadataSectionName()`), а не `MetadataSectionType` |
| 7 | `IoSupplier` | `net.minecraft.server.packs.resources.IoSupplier` |
| 8 | `JukeboxPlayable` | `record JukeboxPlayable(EitherHolder<JukeboxSong> song, boolean showInTooltip)` — именно `EitherHolder` (у него есть удобный конструктор `EitherHolder(Holder<T>)`) |
| 9 | `Item#use` | возвращает `InteractionResultHolder<ItemStack>` (НЕ `InteractionResult` — это уже 1.21.2+) |
| 10 | `Registries.ITEM` | ключ реестра предметов — `Registries.ITEM` (единственное число) |
| 11 | `PacketDistributor` | `sendToServer(payload)`, `sendToPlayer(ServerPlayer, payload)` — самый стабильный способ отправки |
| 12 | network API | `RegisterPayloadHandlersEvent` (`net.neoforged.neoforge.network.event`), `event.registrar("1")`, `PayloadRegistrar.playToClient/playToServer(Type, StreamCodec<? super RegistryFriendlyByteBuf, T>, IPayloadHandler)`; `IPayloadContext`/`IPayloadHandler` — в `net.neoforged.neoforge.network.handling` (`context.enqueueWork(Runnable)`, `context.player()`) |
| 13 | `AddPackFindersEvent` | `getPackType()` + `addRepositorySource(RepositorySource)`; `RepositorySource.loadPacks(Consumer<Pack>)` — 1 аргумент |
| 14 | `GuiGraphics` | `drawCenteredString(Font, Component, x, y, color)` — 5 аргументов (без boolean); `drawString(Font, Component, x, y, color, boolean)` — есть |
| 15 | `AbstractSelectionList` | нет `updateSize(w,h,y0,y1)`; вместо него `updateSizeAndPosition(width, contentHeight, headerTop)`; `ObjectSelectionList(Minecraft, width, height, y0, itemHeight)` |
| 16 | `Entry#render` | `render(GuiGraphics, index, top, left, width, height, mouseX, mouseY, hovered, partialTick)` — 10 параметров, актуально |
| 17 | `Commands` | `performPrefixedCommand(CommandSourceStack, String)` — есть |
| 18 | `Minecraft` | `reloadResourcePacks()` — существует (ClientReloadUtil ищет его рефлексией по списку имён на случай минорных переименований) |
| 19 | `Level` | `isClientSide` — публичное ПОЛЕ (не метод); `Level#playSound(Player, BlockPos, SoundEvent, SoundSource, float, float)` — есть |
| 20 | `Item.use` у чистой пластинки | сервер шлёт пакет через `PacketDistributor.sendToPlayer`, возвращает `InteractionResultHolder.sidedSuccess(stack, level.isClientSide)` |

## Про сборку в стеснённых условиях и воспроизводимость

Пайплайн NeoForm (decompile → patch → recompile) запускается локально при первой
сборке. Практические наблюдения:

- **VineFlower чувствителен к патч-версии JDK**: канонический вывод, против которого
  сгенерированы патчи NeoForge, получен на JDK 21.0.3/21.0.4 (эпоха 1.21.1). Более
  новые 21.0.x дают немного другой вывод (например, лишние/недостающие generic-касты),
  из-за чего часть патчей может отвергнуться. **Рекомендация: собирать на Temurin
  21.0.4** (CI пинит именно его). Также замечен недетерминизм VineFlower между прогонами
  на одном JDK при нехватке памяти/ядер — на обычных машинах с `-Xmx4G` (задано в
  конфиге NeoForm) всё стабильно.
- VineFlower'у нужно `-Xmx4G` (задаётся самим NeoForm-конфигом) — на машинах с <4 ГБ
  свободной памяти самый большой класс (`Blocks.java`) может не декомпилироваться
  («Couldn't be decompiled», дамп байткода). Решается свободной памятью.
- Шаги NeoForm кешируются в `~/.gradle/caches/ng_execute/<hash>/{output,lock,healthy}`
  (отпечаток = входы шага + имя стадии) — повторные сборки идут быстро.
- `neoFormPatch` применяет 83 патча NeoForm (декомпиляторные фиксы, лежат в
  `build/neoform/<dist>/patches/joined`), затем `neoFormPatchUserDev` применяет 741
  патч NeoForge (из userdev-jar, распакованного в `build/tmp/.cache/expanded/...`);
  уже применённые ханки патчер распознаёт и пропускает.

## Принятые решения (чтобы не потерять контекст)

- **Пул слотов вместо одного SoundEvent + подмены потока:** подмена аудиопотока
  требует миксинов в `SoundEngine`/`SoundManager` и хрупка к обновлениям. Пул из
  128 зарегистрированных событий + детерминированная раздача слотов даёт
  **полностью ванильный** путь воспроизведения при нулевых миксинах в движок.
- **Виртуальный датапак вместо миксина в `RegistryDataLoader`:** `AddPackFindersEvent`
  — публичное API NeoForge; `jukebox_song` грузится штатно и **синхронизируется
  ванилью** на клиенты (синхронизируемый датапак-реестр с 1.20.5).
- **Единый кеш на клиенте** (`config/custommusic/client-cache/`) и в одиночной
  игре, и на выделенном сервере — один и тот же код синхронизации (в одиночке
  пакеты идут по loopback-соединению).
- **Слоты присылаются сервером** в трек-листе, клиент не вычисляет их сам —
  значит, перебор слотов при добавлении треков ничего не ломает на клиенте.
- **Команды** регистрируются только через `RegisterCommandsEvent` (он серверный;
  для клиентских команд в NeoForge есть отдельный `RegisterClientCommandsEvent`).
- **Синхронизация скорости `chunksPerTick`** через `MinecraftServer#execute`
  (никаких `TickEvent` — их пакеты/сигнатуры менялись в 21.x).

## Локальный maven-repo `repo/` и динамические версии (важно для CI)

`build.gradle` резолвит `dev.custommusic:audio-codecs:[1.0,)` и
`dev.custommusic:jvorbisenc-patched:[0.0.3,)` из project-local репозитория
`repo/`. Диапазон версий требует ПЕРЕЧИСЛИТЬ доступные версии, и Gradle
(проверено на 8.10, строка CI) **не листает директории maven-репозитория** —
нужен `repo/dev/custommusic/<artifactId>/maven-metadata.xml`. Без него даже
при полностью закоммиченных jar/pom сборка падает на построении графа задач
`build` с «Could not determine the dependencies of task ':jarJar' →
Could not find any matches for dev.custommusic:audio-codecs:[1.0,)».
Эмпирический чек (Gradle 8.10, локальный file-maven-repo): диапазон без
метаданных — FAIL, с метаданными — `[1.0,) -> 1.0` OK, статическая версия — OK.

Метаданные пишутся руками (артефакты пересобираются редко, maven-publish тут
не подключён). CI перед сборкой проверяет наличие всех шести файлов repo/
(2 jar + 2 pom + 2 metadata) и печатает внятную ошибку вместо загадочной.
Состав `maven-metadata.xml`: groupId/artifactId/versioning{latest, release,
versions[], lastUpdated}. При добавлении новой версии артефакта добавьте
`<version>` и обновите `latest`/`release`.

## Проверенная механика vanilla 1.21.1 (по байткоду client.jar + официальные маппинги)

- **Чтение sounds.json (SoundManager.prepare):** для КАЖДОГО namespace из
  `ResourceManager.getNamespaces()` берётся список
  `getResourceStacks(ResourceLocation.fromNamespaceAndPath(ns, "sounds.json"))`
  — это доходит до `PackResources.getResource(PackType, id)` по всем пакам по
  очереди. Для виртуального пака достаточно корректно отвечать в `getResource`
  и в `getNamespaces`; `listResources` для sounds.json НЕ используется.
- **Ключи sounds.json:** id события = `ResourceLocation.fromNamespaceAndPath(
  <namespace пака>, <ключ из JSON>)`. Ключи обязаны быть БЕЗ префикса
  (`slot_7`), иначе получится `custommusic:custommusic:slot_7` и событие не
  найдётся. (Ошибка 1.0.1: ключи писались как `custommusic:slot_7`.)
- **Поле "name" у звука:** `FileToIdConverter("sounds", ".ogg")` строит путь
  файла `sounds/<path>.ogg` в namespace'е; `custommusic:tracks/<slug>` →
  `assets/custommusic/sounds/tracks/<slug>.ogg` — префикс namespace в "name"
  разрешён и нужен.
- **`Minecraft.reloadResourcePacks()`** существует (публичный, возвращает
  CompletableFuture) — рефлексия в ClientReloadUtil находит его; F3+T — тот же путь.
- **Кодировщик jVorbisEnc:** вывод проверен побайтово (структура Ogg, CRC, BOS/
  EOS, гранулы) и декодером stb_vorbis (тот же, что в Minecraft через LWJGL) —
  полностью валиден. Эмпирический тест: синус 5 с → 220500 сэмплов. vorbisspi
  (jorbis) на этом же файле декодирует 0 фреймов — это причуда vorbisspi
  (важно только для ре-декодирования стерео-OGG-источников, не для воспроизведения).

## Сборка без полного пайплайна (если окно сборки ограничено)

Шаг VineFlower-декомпиляции не всегда укладывается в лимит времени. Компромисс —
скомпилировать мод javac'ом против артефактов, которые пайплайн уже создал
(шаг rename завершается быстро):

1. Прогнать `./gradlew build` пока не завершатся шаги до `neoFormRename`;
   отображённый `build/neoForm/.../steps/rename/output.jar` — это маппнутый,
   стрипнутый, merged-классфайл Minecraft 1.21.1 (нужен как classpath).
2. Скачать `neoforge-21.1.77-universal.jar`, `fancymodloader:loader:4.0.31`,
   `net.neoforged:bus:8.0.2`, **`net.neoforged:mergetool:2.0.3:api`** (там живёт
   `net.neoforged.api.distmarker.Dist`!), `net.fabricmc:sponge-mixin`,
   `com.google.code.findbugs:jsr305:3.0.2` (для javax.annotation.Nullable),
   и взять 97 библиотек из `steps/listLibraries/libraries.txt`.
3. `javac --release 21 -proc:none -cp <все jar'ы + repo-кодеки> -d out src/...`
   (47 класса). Собрать jar: классы + ресурсы + `META-INF/jarjar/*` из
   предыдущей сборки + версия в `neoforge.mods.toml`. CI (GitHub Actions)
   всё равно пересобирает каноничным путём.

## Известные упрощения

- Конвертация читает PCM в память (до ~256 МБ guard). Для стриминговой
  перекодировки оберните downmix/resample в собственный `AudioInputStream`.
- Скан папки не рекурсивный (только `config/custommusic/music/`, без подпапок).
- `comparator_output` всегда 15 (можно считать хеш от slug % 15 для «разных»
  значений).
- Длительность `.mp3` известна только после декодирования — лимит длительности
  проверяется постфактум (файл декодируется, потом отбрасывается, если длиннее).
- Один миксин (`SoundEngineMixin`) — чисто диагностический: подсказка в лог,
  если слот ещё не скачан клиентом.
