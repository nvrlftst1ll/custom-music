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
| 21 | `SoundManager` (1.21.1) | `net.minecraft.client.sounds.SoundManager` (это то, что возвращает `Minecraft.getSoundManager()`; НЕ `resources.sounds`); `play(SoundInstance)`, `stop(SoundInstance)`, `isActive(SoundInstance)` |
| 22 | `SimpleSoundInstance` | публичный ctor `SimpleSoundInstance(ResourceLocation, SoundSource, float volume, float pitch, RandomSource, boolean looping, int delay, Attenuation, double x, double y, double z, boolean relative)`; поля `AbstractSoundInstance` (volume, x, y, z, looping, attenuation, relative, delay) — protected |
| 23 | `BlockEntity` (1.21.1) | НЕТ `onLoad()`/`onChunkUnloaded()` (появились позже); хуки: `setLevel(Level)` (вызывается и при загрузке чанка в LevelChunk) + NeoForge `ChunkEvent.Load/Unload` (game-bus) + `setRemoved()`; `loadAdditional(CompoundTag, HolderLookup.Provider)` — **protected** (не public) |
| 24 | `BlockEntityType.Builder.of` | параметр `BlockEntityType$BlockEntitySupplier` — package-private; в канонической сборке его расширяет NeoForge accesstransformer, а для компиляции против сырого rename-jar нужен compile-only патч флагов (см. `patch_access.ts`) |
| 25 | `BaseEntityBlock` | абстрактный `protected MapCodec<? extends BaseEntityBlock> codec()` — обязателен к override; `Block.simpleCodec(MyBlock::new)` подходит; `getRenderShape` по умолчанию INVISIBLE — вернуть `RenderShape.MODEL`, иначе блок невидим; `createTickerHelper(given, expected, ticker)` — static protected |
| 26 | `BlockBehaviour` | `useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult)` — protected, возвращает `InteractionResult`; `onRemove(oldState, Level, pos, newState, boolean)` — protected |
| 27 | `ChunkEvent` | `net.neoforged.neoforge.event.level.ChunkEvent.Load/Unload` (game-bus); `getChunk(): ChunkAccess` — у `ChunkAccess` НЕТ `getBlockEntities()`, каст к `LevelChunk` (у него `public Map<BlockPos, BlockEntity> getBlockEntities()`); `getLevel(): LevelAccessor` |
| 28 | `ClientTickEvent` | `net.neoforged.neoforge.client.event.ClientTickEvent.Post/Pre` (game-bus, классы-вложенцы без полей) |
| 29 | `Math.clamp` | есть в JDK 21, но в коде мода используем `Mth.clamp(...)` — не зависит от версии JDK |
| 30 | `MapColor` | `net.minecraft.world.level.material.MapColor`, константы `MapColor.COLOR_BROWN` и т.п. |

## Механика звука 1.21.1, проверенная по декомпилированному SoundEngine (для Juke Box)

- **Кого слышно и как далеко.** В `SoundEngine.play(instance)` НЕТ cull-дистанции
  (легенда про «max(volume,1)×16» относится к старым версиям). Есть линейное
  затухание: `f1 = Math.max(volume, 1.0F) * sound.getAttenuationDistance()` и
  `Channel.linearAttenuation(f1)`: `AL_DISTANCE_MODEL = AL_LINEAR`,
  `AL_ROLLOFF_FACTOR = f1`, `AL_REFERENCE_DISTANCE = 1.0`,
  `AL_MAX_DISTANCE = 0.0`. Т.е. звук затухает до нуля ровно на `f1` блоках
  (`attenuationDistance` берётся из поля `attenuation_distance` в sounds.json,
  по умолчанию 16).
- **Громкость клампится.** `SoundEngine.calculateVolume(volume, source)` =
  `Mth.clamp(volume * categoryVolume, 0, 1)` — громкость > 1 НЕ делает звук
  громче, только расширяет радиус затухания. Поэтому слайдер громкости
  Juke Box — 10–100 %, а «громче ванильного» не бывает.
- **Дальность отдельна от громкости.** Единственный способ задать радиус
  независимо — `Sound.getAttenuationDistance()`. Мод оборачивает разрешённый
  `Sound` в `RangedSound extends Sound`, у которого
  `getAttenuationDistance() = range`. Обёртка корректна и для
  EMPTY_SOUND-случая: `resolve()` возвращает null → экземпляр помечает
  `resolved = false` и НЕ оборачивает, сохраняя ванильные предупреждения
  «Unable to play unknown/empty soundEvent».
- **Зацикливание стриминга работает.** `Channel.setLooping(looping && !streaming)`
  (OpenAL-луп для буферов в памяти), а для стриминговых звуков
  `SoundBufferLibrary.getStream(path, looping)` возвращает
  `LoopingAudioStream` — бесшовный повтор OGG-потока. Ручной пере-старт
  нужен только при `delay > 0` (мы не используем).
- **Много одновременно играющих Juke Box** держат по одному OpenAL
  streaming-каналу каждый — пул каналов ограничен (у OpenAL Soft ~255 суммарно,
  стриминговых меньше); на практике десяток ящиков безопасен.
- **`ServerLevel.players()`** возвращает `List<ServerPlayer>` — итерация по
  нему даёт только игроков этого измерения (проверки «другого измерения»
  не нужны); `Level.random` — публичное поле `RandomSource`.

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
(шаг rename завершается быстро). Всё автоматизировано тремя скриптами:

0. Предусловия: `./gradlew build` хотя бы раз дойти до `:neoFormRename` (даёт
   `build/neoForm/<id>/steps/rename/output.jar` — маппнутый, стрипнутый,
   merged Minecraft 1.21.1 — главный classpath) и gradle-toolchain JDK
   (`~/.gradle/jdks/eclipse_adoptium-21…/bin/javac`). Так же нужны в classpath:
   `neoforge-21.1.77-universal.jar`, `fancymodloader:loader:4.0.31`,
   `net.neoforged:bus:8.0.2`, **`net.neoforged:mergetool:2.0.3:api`** (там живёт
   `net.neoforged.api.distmarker.Dist`!), `net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7`,
   `com.google.code.findbugs:jsr305:3.0.2` (для javax.annotation.Nullable),
   97 библиотек из `steps/listLibraries/libraries.txt` и кодеки из `repo/`.
1. **`bun run patch_access.ts`** — патчит `BlockEntityType$BlockEntitySupplier`
   в compile-only копии jar'а (public + InnerClasses-флаги; ровно то, что в
   канонической сборке делает NeoForge accesstransformer). Без этого javac
   отвергает `BlockEntityType.Builder.of(MyBE::new, …)` — интерфейс
   package-private. Исправлен нюанс парсера: CONSTANT_Dynamic (тег 17)
   занимает 4 байта, как InvokeDynamic, а не 2.
2. **`bash compile_sandbox.sh`** — javac всех исходников (107 записей classpath).
3. **`bash package_sandbox.sh`** — собирает дистрибутивный jar: классы + ресурсы +
   раскрытые placeholder'ы в `neoforge.mods.toml` + `META-INF/jarjar/*`
   (кодеки и metadata.json как в каноническом jarJar-выходе). Результат кладётся
   в `build/libs/custommusic-<version>-all.jar`. Проверено: у собранного так
   1.0.2-jar байт `[[mods]]` в toml валиден (hexdump), классы корректны.

Рефмапа для миксина не нужно: с 1.20.2 NeoForge исполняет Minecraft в mojmap-
именах в рантайме, поэтому `@Mixin`-цель `play(Lnet/minecraft/client/resources/
sounds/SoundInstance;)V` работает и в проде (подтверждено 1.0.2).
CI (GitHub Actions) всё равно пересобирает каноничным путём.

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

### Упрощения Juke Box (1.1.0)

- Игрок, вошедший в радиус СЕРЕДИНЫ трека, слышит его с начала (ванильный
  звуковой движок не умеет seek). Кто был в радиусе с самого старта — слышат
  без прерываний.
- Смена трека/громкости/дальности/зацикливания во время игры перезапускает
  экземпляр у всех слушателей с начала трека (нет live-обновления gain; seek
  невозможен). Слайдеры деблоунсятся (отправка ≤ 2 раза/с, форс при отпускании).
- Если сервер держит дистанцию стрима меньше радиуса (simulation distance <
  range/32+), чанк с Juke Box может выгрузиться при игроках в радиусе — на этот
  случай `suspendForUnload()` честно рассылает стоп и состояние NBT сохраняется.
- Один Juke Box = один OpenAL streaming-канал на клиента; десятки одновременно
  играющих боксов могут упереться в лимит каналов (звук молча не стартует,
  в IDE-логе будет «Failed to create new sound handle»).

## Ретро-тест 1.1.1 на живом сервере (эмпирика, не теория)

Воспроизведение бага «reload → scan failed — NoClassDefFoundError:
net/sourceforge/jaad/mp4/MP4Container» делалось на НАСТОЯЩЕМ выделенном
NeoForge-сервере 21.1.77 (`neoforge-installer --installServer`, RCON для команд):

- **Доставляемый jar 1.1.0 работоспособен на чистом сервере**: JiJ-библиотеки
  грузятся (`Found library file "audio-codecs-1.0.jar" [parent: …]`),
  `Night Owl.ogg` (mono 44.1k → passthrough) и `Test Song.m4a` (JAAD-декодер +
  jVorbisEnc-энкодер) конвертируются за ~5 с, reload переиспользует кеш.
  Значит мгновенный `NoClassDefFoundError` у пользователя — свойство ЕГО
  окружения (старый/промежуточный jar в mods, конфликт с другим модом,
  причуды лаунчера), а не кода мода.
- **Механика бага подтверждена**: `JaadAudio.decodeMp4` ссылается на классы
  JAAD напрямую → если модуль `audio-codecs` не загрузился, первый же `.m4a`
  даёт `NoClassDefFoundError`. Это **`Error`, а не `Exception`** — он обходил
  ВСЕ `catch (Exception)` (пофайловый guard в TrackStore, decoder/encoder
  guards в AudioConverter) и убивал нить скана целиком; выживал только
  `catch (Throwable)` в команде reload с фразой «scan failed». Один битый
  m4a-файл = reload полностью бесполезен.
- **Проверка фикса 1.1.1 (jar с вырезанным audio-codecs + ffmpegPath="" —
  окружение пользователя без ffmpeg)**: скан завершается, m4a пропускается
  пофайлово с понятной причиной («no JavaSound decoder … ffmpeg fallback is
  disabled»), в latest.log — WARN-блок «Some bundled audio codecs are NOT
  available» с перечнем, рабочий `.ogg` продолжает играть, в чате —
  ✗-строка вместо «scan failed».
- **Нюанс тест-стенда**: в песочнице есть ffmpeg — мод конвертирует m4a даже
  без JAAD через Phase-3 fallback (это не баг, а фича; у пользователя ffmpeg
  нет, поэтому для честного теста ffmpegPath="" обязателен).
- **Тулчейн песочницы восстанавливается** (после очистки /tmp и ~/.gradle):
  1. JDK: `curl -L api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse`
     → распаковать → `ln -s` на `~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2`
     (этот путь зашит в compile_sandbox.sh / package_sandbox.sh / patch_access.ts).
  2. `JAVA_HOME=<jdk> CM_LOWMEM=1 ./gradlew --no-daemon neoFormRename neoFormListLibraries`
     (~2 мин; даёт steps/rename/output.jar + steps/listLibraries/libraries.txt).
  3. `bun run patch_access.ts` (патчит compile-only копию minecraft jar).
  4. `/tmp/modcp/*.jar`: loader-4.0.31 и neoforge-21.1.77-universal есть на
     maven.neoforged.net (universal — также в установленном сервере), bus-8.0.2 —
     `net/neoforged/bus/8.0.2`, sponge-mixin — `net/fabricmc/sponge-mixin/0.15.2+mixin.0.8.7`,
     mergetool-2.0.3-**api**-классификатор (там живёт `Dist`!), jsr305, кодеки из `repo/`.
  5. `bash compile_sandbox.sh && bash package_sandbox.sh` (второй теперь
     сам пишет metadata.json и БАЙТ-ПРОВЕРЯЕТ jar: заголовки `[[mods]]`/`[[mixins]]`,
     JSON jarjar-метаданных, вложенные кодеки c MP4Container.class внутри,
     классы Juke Box, текстуры, рецепты).
- **Про `[ods]]`-фантом**: цепочка инструментов, через которую смотрятся файлы,
  может показывать `[[mods]]` как `[ods]]` (артефакт отображения!). DEVNOTES
  уже писал об этом; `od -c` показывает истину — файл содержит `[[mods]]`.
  night-config 3.6.7 при этом парсит даже ИСКАЖЁННЫЙ `[ods]]` как валидный
  `[[mods]]` (проверено напрямую) — ещё одна причина не доверять терминалу.
