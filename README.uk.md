# PhysEx

**Мови:** [English](README.md) | **Українська**

**PhysEx** — серверний плагін скінченної фізики води та лави для **Paper / Purpur** і **Folia** (`folia-supported: true`, Minecraft **1.21+**, Java 21) від **Yunexal**.

---

## Можливості

- **Справжня скінченна вода та лава** (`finite-mode`): вимикає ванільне розмноження нескінченних 2×2 джерел; об'єм рідини зберігається.
- **Гравітація та вирівнювання рівнів** (`gravity-enabled`, `equalize-enabled`, `min-spread-difference`): вода й лава стікають донизу та вирівнюють рівень поверхні, не розтікаючись тонкою плівкою нескінченно по рівнині.
- **Гідростатичний тиск** (`pressure-enabled`, `pressure-max-depth`, `pressure-max-visited`): сполучені судини та прорив резервуарів із підйомом води/лави під тиском.
- **Витіснення блоками та поршнями** (`displace-on-block-place`, `displace-on-piston`): встановлений у рідину суцільний блок або хід поршня витісняє об'єм замість його знищення.
- **Waterlogging та крихкі блоки** (`waterlog-capable-blocks`, `break-fragile-blocks`, `break-and-burn-fragile-blocks`): заповнення плит/сходів/люків/парканів та руйнування трави, квітів, саджанців, смолоскипів і редстоуну потоками.
- **Побутові взаємодії**:
  - Наповнення скляної пляшки споживає воду (`consume-on-bottle-fill`, `bottle-volume-cost`).
  - Відро може збирати сусідні неповні калюжки до повного об'єму (`bucket-scoop-partial-volume`).
  - Зрошення ріллі поступово витрачає воду (`consume-on-farmland-hydrate`, `farmland-consume-chance`).
  - Вимкнення автоматичного наповнення казанів від капельників (`disable-dripstone-cauldron-fill`).
  - Висихання тонких калюжок у сухих/жарких біомах (`evaporate-thin-puddles-in-dry-biomes`, `thin-puddle-evaporate-chance`) та опційне наповнення дощем (`rain-fills-world`).
  - Три режими реакції води й лави (`water-lava-interaction.mode`): `PHYSEX_EVAPORATE`, `CONSUME_WATER_STONE`, `VANILLA`.
  - Танення льоду активує фізичну воду (`melting-ice-creates-finite-water`), а `unfreeze-marine-flora-on-drain` розморожує підводну флору під час дренажу.
- **Багатомовність (i18n)**:
  - Вбудовані мови: англійська (`en`, за замовчуванням), українська (`uk`), польська (`pl`), німецька (`de`) та іспанська (`es`) у `plugins/PhysEx/lang/<code>.yml`.
  - Автовизначення локалі клієнта для кожного гравця (`general.per-player-locale: true`) із резервною мовою сервера (`general.language: en`).
  - Підтримка власних перекладів через додавання файлу `<code>.yml` у `plugins/PhysEx/lang/`.
- **Фонова асинхронна обробка, захист TPS і підтримка Folia**:
  - Автоматичне визначення середовища (`Paper/Purpur` або `Folia (Regionized)`).
  - За замовчуванням (`general.async-processing: true`) важкі обчислення фізики, перетікання й BFS гідростатичного тиску виконуються у фонових робочих потоках (`general.async-worker-threads`) поверх незмінних `ChunkSnapshot`, а на основний/регіональний потік повертаються лише мінімальні перевірені зміни `BlockData` із суворим збереженням об'єму.
  - На Folia черга оновлень шардована по регіонах (`RegionScheduler` / `GlobalRegionScheduler` / `EntityScheduler`), з регіональними перевірками `Bukkit.isOwnedByCurrentRegion(...)` та безпечним перенесенням об'єму між регіонами.
  - Бюджет часу на тік (`max-tick-time-ms`), ліміт оновлень (`max-updates-per-tick`), обмеження черги (`max-queue-size`), авто-тротлінг за порогом TPS (`min-tps-threshold`) і збереження черги при зупинці (`persist-queue-on-shutdown`).

---

## Встановлення та збірка

1. Помістіть `PhysEx.jar` у папку `plugins/` вашого сервера **Paper / Purpur / Folia 1.21+** (Java 21).
2. Перезапустіть сервер — у `plugins/PhysEx/` буде створено `config.yml` і каталог мов `lang/*.yml`.
3. Збірка з вихідного коду:
   ```bash
   mvn package
   ```
   Готовий JAR: `target/PhysEx.jar`.

---

## Команди та права

Право доступу: `physex.admin` (за замовчуванням `op`).  
Аліаси команди `/physex`: `/pe`, `/fluidphysics`, `/fluids`.

- `/physex status` — платформа (`Paper/Purpur` або `Folia (Regionized)`), стан симуляції, TPS, час останнього тіку, розмір черги води/лави та активні режими.
- `/physex reload` — перезавантажити `config.yml` і файли `lang/*.yml` без перезапуску сервера.
- `/physex pause` / `/physex resume` — призупинити або відновити симуляцію рідин.
- `/physex clearqueue` — очистити поточні черги активних потоків.
- `/physex drain <радіус 1..32> [water|lava]` — осушити рідини в заданому радіусі навколо гравця.
- `/physex fill <радіус 1..32> [water|lava]` — заповнити область водою або лавою.
- `/physex set <ключ> <значення>` — змінити параметр `config.yml` на льоту зі збереженням (наприклад, `/physex set general.language uk` або `/physex set water.finite-mode false`).

---

## Ключові параметри `config.yml`

- `general`: `enabled`, `async-processing`, `async-worker-threads`, `language`, `per-player-locale`, `worlds-whitelist`, `worlds-blacklist`, `max-tick-time-ms`, `max-updates-per-tick`, `max-queue-size`, `min-tps-threshold`, `persist-queue-on-shutdown`, `unfreeze-marine-flora-on-drain`.
- `water`: `enabled`, `tick-interval`, `finite-mode`, `gravity-enabled`, `equalize-enabled`, `min-spread-difference`, `pressure-enabled`, `pressure-max-depth`, `pressure-max-visited`, `displace-on-block-place`, `displace-on-piston`, `waterlog-capable-blocks`, `break-fragile-blocks`, `consume-on-bottle-fill`, `bottle-volume-cost`, `bucket-scoop-partial-volume`, `consume-on-farmland-hydrate`, `farmland-consume-chance`, `disable-dripstone-cauldron-fill`, `evaporate-thin-puddles-in-dry-biomes`, `thin-puddle-evaporate-chance`, `rain-fills-world`, `rain-fill-attempts-per-tick`.
- `lava`: `enabled`, `tick-interval`, `nether-tick-interval`, `finite-mode`, `gravity-enabled`, `equalize-enabled`, `min-spread-difference`, `pressure-enabled`, `pressure-max-depth`, `pressure-max-visited`, `displace-on-block-place`, `displace-on-piston`, `break-and-burn-fragile-blocks`, `disable-dripstone-cauldron-fill`, `bucket-scoop-partial-volume`.
- `water-lava-interaction`: `mode` (`PHYSEX_EVAPORATE` / `CONSUME_WATER_STONE` / `VANILLA`), `evaporate-effects`.
- `ice-and-sponge`: `melting-ice-creates-finite-water`.
