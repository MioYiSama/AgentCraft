# AgentCraft — LLM agents controlling fake players (Paper 26.3)

## Context

The repo `/Users/mioyi/Documents/Code/Java/AgentCraft` is empty and has no git. Build a server plugin from scratch. `/agent start <prompt>` creates a server-wide LLM agent with a 4-digit id. It also creates a Carpet-style fake player: a real logged-in NMS `ServerPlayer`. The agent controls that player through Koog tool calls and stands still when it finishes. `/agent chat <id> <prompt>` either wakes an idle agent or gives instructions to a working one. `/agent list|view|stop|destroy` manage agents. Every `/agent` command requires op.

Stack: Kotlin 2.4.21, Koog 1.3.0, local Gradle 9.8.2 (no wrapper), Java 25, Paper 26.3. Layout: `core/` holds platform-neutral code; `platforms/bukkit/` is the only platform in v1. The LLM uses the OpenAI Responses API only. The API key and base URL come from the plugin config.

User decisions already baked in:
- Standard survival tool set.
- Sessions persist across restarts and come back idle.
- Shadow fat jar.
- The AI picks the fake player's name.

## Verified facts the plan relies on

- **Versions found:**
  - Kotlin plugin `org.jetbrains.kotlin.jvm` / `org.jetbrains.kotlin.plugin.serialization` 2.4.21
  - `ai.koog:koog-agents:1.3.0`, built with Kotlin 2.3.10 / JVM 17; it pulls in Ktor 3.3.3, `ai.koog:http-client-ktor` and `io.ktor:ktor-client-apache5`
  - `kotlinx-coroutines-core` 1.11.0
  - `kotlinx-serialization-json` 1.12.0
  - JUnit BOM 6.1.3
  - Shadow `com.gradleup.shadow` 9.6.1
  - `io.papermc.paperweight.userdev` 2.0.0-beta.24
  - `xyz.jpenilla.run-paper` 3.1.0
  - Paper dev bundle `26.3.build.231-stable` (repo `https://repo.papermc.io/repository/maven-public/`)
- **Compatibility caveats:**
  - KGP 2.4.21 officially lists Gradle ≤ 9.7.0. Gradle 9.8.2 may only print deprecation warnings.
  - MC 26.x is unobfuscated. Do **not** configure `reobfJar` or `paperweight.reobfArtifactConfiguration`.
- **Koog 1.3.0 APIs (read from source at tag 1.3.0):**
  - Client and settings:
    - `OpenAIClientSettings(baseUrl, timeoutConfig, chatCompletionsPath, responsesAPIPath = "v1/responses", …)`
    - JVM factory `OpenAILLMClient(apiKey: String, settings: OpenAIClientSettings)`. It finds the HTTP client factory through `ServiceLoader` and throws if it finds zero or more than one.
    - The Ktor factory makes sure the base URL ends with `/` before joining. So `baseUrl="https://x/v1"` with `responsesAPIPath="responses"` gives `https://x/v1/responses`.
  - Endpoint selection: the client uses Responses only when the prompt params are `OpenAIResponsesParams` **and** the model has `LLMCapability.OpenAIEndpoint.Responses`. If the host contains `openai.azure.com`, it always switches to chat completions.
  - `LLModel(provider, id, capabilities: List<LLMCapability>?, contextLength: Long?, maxOutputTokens: Long?)`.
  - `MultiLLMPromptExecutor` (`ai.koog.prompt.executor.llms`) is `AutoCloseable`. Its `execute(prompt, model, tools = emptyList()): Message.Assistant` can be used for one-off calls.
  - Agent construction:
    - `AIAgent(promptExecutor, agentConfig, strategy: AIAgentFunctionalStrategy<I,O>, toolRegistry, …)`
    - `AIAgentConfig(prompt, model, maxAgentIterations, …)`
    - `functionalStrategy<I,O>(name) { input -> … }`
    - Functional loops have **no** automatic iteration bound.
  - Prompt manipulation inside the functional context:
    - `llm.writeSession { appendPrompt { … }; requestLLM() }`: `requestLLM()` appends the assistant response to the prompt.
    - `appendPrompt` alone does not call the LLM.
    - `llm.readSession { prompt }` returns a consistent snapshot. Never nest read or write sessions.
    - The functional context also has `executeTools(calls: List<MessagePart.Tool.Call>, parallelTools = false): List<ReceivedToolResult>`, and `ReceivedToolResult.toMessagePart()`.
  - Prompt builder:
    - `PromptBuilder.user(content: String)`
    - `user { toolResult(part) }`
    - `RequestMessagePartsBuilder.toolResult(id: String?, tool: String, output: String, isError: Boolean = false)`
  - Message types:
    - Tool calls are `MessagePart.Tool.Call(id: String?, tool: String, args: String /*JSON*/)` and appear inside `Message.Assistant.parts`.
    - `Message.Assistant.textContent()` joins the text parts.
    - `ai.koog.prompt.Prompt(messages: List<Message>, id: String, params: LLMParams)` and `ai.koog.prompt.message.Message` are `@Serializable`.
    - `OpenAIResponsesParams` is **not** serializable, so it must be applied again after loading.
  - Tools:
    - `SimpleTool<Args>(argsType = typeToken<Args>(), name, description)` with `override suspend fun execute(args: Args): String`.
    - Args are `@Serializable` data classes. Properties carry `@property:LLMDescription("…")`.
    - Imports: `ai.koog.serialization.typeToken`, `ai.koog.agents.core.tools.SimpleTool`, `ai.koog.agents.core.tools.ToolRegistry`, `ai.koog.agents.core.tools.annotations.LLMDescription`.
    - `ToolRegistry { tool(x) }`.
- **Paper NMS fake player:** the closest working reference is FakePlayer-CE 26.2 at commit `7d064c0`, https://github.com/EndlessPixel/FakePlayer-CE. The steps:
  1. Construct with `ServerPlayer(server, level, GameProfile(uuid, name), ClientInformation.createDefault())`.
  2. Load data explicitly: `server.playerList.playerIo.load(handle.nameAndId())` → `handle.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag))`.
  3. Place with `server.playerList.placeNewPlayer(fakeConnection, handle, CommonListenerCookie.createInitial(profile, false))`.
  4. Replace `handle.connection` with a subclass of `ServerGamePacketListenerImpl`.
  5. Because the fake `Connection` is never added to the server's connection list, nothing ticks the listener. The plugin must call `handle.doTick()` every tick from the scheduler.
  6. Removal **must** call `server.playerList.remove(handle)`. Kicking does not disconnect a fake connection. `remove` saves player data only when `bukkitEntity.isPersistent`.
  7. In Paper 26.2 `Connection.channel` and `Connection.address` are public fields.
  8. `PlayerList.respawn` creates a **new** `ServerPlayer` object and copies over the connection. Never cache the handle; look it up by UUID every time.
- **Carpet movement** (`EntityPlayerActionPack`): set `player.zza` / `player.xxa` / `setJumping(…)` every tick before physics runs.
- **No Paper API exists for player pathfinding.** `Mob#getPathfinder` only works on mobs.

## Approach

Base package: `io.github.mioyi.agentcraft`. Core code lives under `…agentcraft.core`; Bukkit code lives under `…agentcraft.bukkit`. Do steps 1–6 in order. Steps 2–4 are pure core code, and each comes with its own unit tests. Steps 5–6 depend on 1–4.

### 1. Gradle build skeleton

- `settings.gradle.kts`:
  - `pluginManagement { repositories { gradlePluginPortal(); maven("https://repo.papermc.io/repository/maven-public/") } }`
  - `rootProject.name = "AgentCraft"`
  - `include(":core", ":platforms:bukkit")`
  - Do **not** use `dependencyResolutionManagement` with `FAIL_ON_PROJECT_REPOS`, because paperweight adds project repositories. Declare repositories in each module instead.
- `gradle/libs.versions.toml` holds:
  - Versions: `kotlin=2.4.21`, `koog=1.3.0`, `coroutines=1.11.0`, `serialization=1.12.0`, `junit=6.1.3`, `shadow=9.6.1`, `paperweight=2.0.0-beta.24`, `runpaper=3.1.0`, `paper=26.3.build.231-stable`
  - Libraries: `koog-agents` (`ai.koog:koog-agents`), `coroutines-core`, `serialization-json`, `junit-bom`
  - Plugins: `kotlin-jvm`, `kotlin-serialization`, `shadow`, `paperweight` (`io.papermc.paperweight.userdev`), `runpaper` (`xyz.jpenilla.run-paper`)
- Root `build.gradle.kts`: declare every plugin alias with `apply false`, plus `allprojects { group = "io.github.mioyi.agentcraft"; version = "0.1.0" }`.
- `gradle.properties`: `kotlin.code.style=official`.
- `core/build.gradle.kts`:
  - Plugins: kotlin-jvm and kotlin-serialization.
  - `repositories { mavenCentral() }`.
  - `kotlin { jvmToolchain(25) }`.
  - Dependencies: `api(libs.koog.agents)`, `api(libs.coroutines.core)`, `api(libs.serialization.json)`.
  - Tests: `testImplementation(kotlin("test-junit5"))`, `testImplementation(platform(libs.junit.bom))`, `testImplementation("org.junit.jupiter:junit-jupiter")`, `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`, `tasks.test { useJUnitPlatform() }`.
- `platforms/bukkit/build.gradle.kts`:
  - Plugins: kotlin-jvm, paperweight, shadow, runpaper.
  - Repositories: `mavenCentral()` and the papermc repo.
  - `kotlin { jvmToolchain(25) }`.
  - Dependencies: `implementation(project(":core"))`, `paperweight.paperDevBundle(libs.versions.paper.get())`.
  - `tasks.processResources { filesMatching("paper-plugin.yml") { expand("version" to project.version) } }`.
  - `tasks.jar { archiveClassifier.set("plain") }`.
  - `tasks.shadowJar`:
    - `archiveClassifier.set("")` and `archiveBaseName.set("AgentCraft")`.
    - `mergeServiceFiles()` and `filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }`.
    - `exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/*/module-info.class")`.
    - **No relocation** (see Assumptions).
  - `tasks.assemble { dependsOn(tasks.shadowJar) }`.
  - `tasks.runServer { minecraftVersion("26.3") }`.
- Done when `gradle build` passes from the repo root, with `core` holding at least one compiling source file and one test.

### 2. Core: configuration, platform contract, logs, persistence

- `core/.../LlmConfig.kt`:
  - `data class LlmConfig(val apiKey: String, val baseUrl: String, val model: String, val maxIterations: Int)`.
  - `fun validate(): String?` returns the first problem, or null when the config is valid.
  - Problems, in this order:
    1. Blank `apiKey` → `"llm.api-key is empty"`.
    2. Blank `baseUrl` → `"llm.base-url is empty"`.
    3. `baseUrl` contains `openai.azure.com` → `"Azure endpoints are not supported (Koog forces chat completions for Azure)"`.
    4. Blank `model` → `"llm.model is empty"`.
    5. `maxIterations !in 1..1000` → `"agent.max-iterations must be 1..1000"`.
- `core/.../Platform.kt`, platform-neutral contract:
  ```kotlin
  data class SpawnPoint(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)
  data class BodySpec(val uuid: UUID, val name: String, val spawn: SpawnPoint?) // spawn == null → keep position loaded from playerdata
  interface Platform {
      val dataDir: java.nio.file.Path
      fun log(message: String)                       // console info
      fun warn(message: String)                      // console warning
      fun notifyOperators(message: String)          // online ops + console
      fun runOnMain(task: () -> Unit)                // schedule, never inline-blocking
      suspend fun isNameAvailable(name: String): Boolean
      suspend fun spawnBody(spec: BodySpec): AgentBody   // throws on failure
      fun deleteBodyData(uuid: UUID)                 // main thread
  }
  interface AgentBody { /* see step 4 */ }
  ```
  Uses `Serializable` `SpawnPoint` (add `@Serializable`; `world` is the namespaced world key, e.g. `minecraft:overworld`).
- `core/.../AgentLog.kt`:
  - `enum class LogKind { USER, ASSISTANT, TOOL_CALL, TOOL_RESULT, EVENT, ERROR }`.
  - `@Serializable data class LogEntry(val time: Long, val kind: LogKind, val text: String)`.
  - `class AgentLog(initial: List<LogEntry>)` is thread-safe with `synchronized`. It has `add(kind, text)`, which truncates text to 2000 chars and keeps the last 100 entries, plus `last(n): List<LogEntry>` and `snapshot(): List<LogEntry>`.
- `core/.../store/AgentStore.kt`:
  - `@Serializable data class AgentRecord(val id: Int, val name: String, val uuid: String, val location: SpawnPoint?, val messages: List<Message>, val log: List<LogEntry>)`. Here `Message` is `ai.koog.prompt.message.Message`. `messages` **excludes** the system message.
  - `@Serializable data class StoreFile(val agents: List<AgentRecord>)`.
  - `class AgentStore(file: Path)`:
    - `load(): List<AgentRecord>`. A missing file returns an empty list. A parse failure logs it, renames the file to `agents.json.broken-<epochMillis>`, and returns an empty list.
    - `save(records)`: write `agents.json.tmp`, then `Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE)`.
    - JSON settings: `Json { ignoreUnknownKeys = true; encodeDefaults = true }`.
  - The file is `<dataDir>/agents.json`.
- Unit test `AgentStoreTest`: round-trip a record whose `messages` contain these, and assert equality after decode:
  - `Message.User`
  - a `Message.Assistant` with a text part and a `MessagePart.Tool.Call(id="c1", tool="walk_to", args="{\"x\":1}")`
  - a `Message.User` containing `MessagePart.Tool.Result`

  Build the messages through the `prompt("t") { user("hi") … }` DSL so constructors stay correct. If the decoded value contains a `type`-discriminator conflict, set `classDiscriminator = "#type"` in the store's `Json`.

### 3. Core: navigation (A*) — independent of Koog

- `core/.../nav/PathFinder.kt`:
  - `enum class Cell { SOLID, PASSABLE, WATER, AVOID }`.
    - `SOLID` blocks movement and can be stood on.
    - `PASSABLE` is air or plants.
    - `WATER` is enterable and swimmable.
    - `AVOID` cannot be entered or stood on: lava, fire, cactus, fences, walls, unloaded chunks.
  - `fun interface BlockView { fun cell(x: Int, y: Int, z: Int): Cell }`.
  - `data class BlockPos3(val x: Int, val y: Int, val z: Int)`.
  - `class PathFinder(private val view: BlockView, private val maxNodes: Int = 4000)` with `fun find(start: BlockPos3, goal: BlockPos3, within: Double): List<BlockPos3>?`.
    - It returns the waypoints after `start`, ending at the reached node.
    - `emptyList()` means `start` already satisfies the goal.
    - `null` means no path.
  - **Standable(p):** `cell(p)` is PASSABLE or WATER, and `cell(p.up)` is PASSABLE or WATER, and either `cell(p.down) == SOLID` or `cell(p) == WATER`.
  - **Neighbors:** only the 4 cardinal directions `d`. For each one, check:
    - **flat:** `standable(p+d)`, cost 1.
    - **step up:** `standable(p+d+up)` and `cell(p+up+up)` is not SOLID or AVOID (head room to jump), cost 2.
    - **drop:** for k in 1..3, the first k where `standable(p+d-k·up)` and every cell from `p+d` down to `p+d-(k-1)·up` is PASSABLE or WATER. Cost `1 + 0.5k`.
  - **Goal test:** Euclidean distance from node to goal ≤ `max(within, 0.0)`.
  - **Heuristic:** Euclidean distance to goal minus `within`, clamped at 0.
  - **Limits:** stop and return null after `maxNodes` expansions. Never expand a node more than `|goal-start|_horizontal + 16` blocks away (horizontally) from `start`. The start node is accepted even if it is not standable.
- Unit test `PathFinderTest` uses an in-memory `BlockView` backed by a map, where the default below y=64 is SOLID and above is PASSABLE. Cases:
  1. Flat line of 5 → path size 5.
  2. A 1-high wall needs a step up → passes, and the path contains the y+1 node.
  3. A 3-high wall in a closed corridor → null.
  4. A lava cell (AVOID) on the straight line in an open field → the path goes around it.
  5. A drop of 3 → passes; a drop of 4 (only route) → null.
  6. `within = 1.0` with the goal inside a solid block next to walkable ground → ends at a neighbor.

### 4. Core: tools, LLM service, agent loop, manager

- **`AgentBody` interface** (in `Platform.kt`). Every method is `suspend`. Bodies run all world access on the server thread themselves. Every method returns a human/LLM-readable `String`. Failures are returned as strings starting with `"Error: "` and are never thrown, except `CancellationException`.
  ```kotlin
  interface AgentBody {
      val uuid: UUID
      val name: String
      suspend fun status(): String
      suspend fun inventory(): String
      suspend fun getBlock(x: Int, y: Int, z: Int): String
      suspend fun scanArea(radius: Int): String
      suspend fun findBlocks(type: String, radius: Int, limit: Int): String
      suspend fun nearbyEntities(radius: Int): String
      suspend fun walkTo(x: Int, y: Int, z: Int, within: Double): String
      suspend fun lookAt(x: Double, y: Double, z: Double): String
      suspend fun jump(): String
      suspend fun breakBlock(x: Int, y: Int, z: Int): String
      suspend fun placeBlock(x: Int, y: Int, z: Int, item: String): String
      suspend fun interactBlock(x: Int, y: Int, z: Int): String
      suspend fun useItem(holdTicks: Int): String
      suspend fun attack(entityUuid: String): String
      suspend fun selectItem(item: String): String
      suspend fun chat(message: String): String
      fun halt()                       // thread-safe: cancel current action, zero inputs on next tick
      fun location(): SpawnPoint?      // main thread only; null if offline
      fun despawn()                    // main thread only; removes the player (saves playerdata)
  }
  ```
- **`core/.../tools/AgentTools.kt`:**
  - `abstract class AgentTool<A>(argsType: TypeToken, name: String, description: String, protected val log: AgentLog) : SimpleTool<A>(argsType, name, description)`.
  - `final override suspend fun execute(args: A): String`:
    1. Log `TOOL_CALL "<name> <args.toString()>"`.
    2. Run `invoke(args)`. Rethrow `CancellationException`. Map any other `Exception` to `"Error: ${e.message}"`.
    3. Truncate the result to 4000 chars.
    4. Log `TOOL_RESULT`.
    5. Return the result.
  - `fun agentTools(body: AgentBody, log: AgentLog): ToolRegistry` registers all the tools below. Tool names and arg names are part of the LLM contract; use exactly:

  | tool | args (defaults, clamps) | description gist |
  |---|---|---|
  | `get_status` | — | position/world/facing/health/food/xp/gamemode/held item/time/weather |
  | `get_inventory` | — | all non-empty slots incl. armor/offhand, selected hotbar slot |
  | `get_block` | `x,y,z: Int` | block type + block data at coords |
  | `scan_area` | `radius: Int = 3` clamp 1..5 | non-air blocks around feet grouped by type, count + up to 5 nearest coords each |
  | `find_blocks` | `type: String`, `radius: Int = 16` clamp 1..24, `limit: Int = 10` clamp 1..20 | nearest blocks of a material (`minecraft:oak_log` or `oak_log`) |
  | `get_nearby_entities` | `radius: Int = 16` clamp 1..32 | up to 20 nearest entities: uuid, type, name, coords, health |
  | `walk_to` | `x,y,z: Int`, `within: Double = 1.0` clamp 0..5 | pathfind and walk (≤64 blocks horizontally per call) |
  | `look_at` | `x,y,z: Double` | turn head/body toward a point |
  | `jump` | — | single jump |
  | `break_block` | `x,y,z: Int` | mine a block in reach with held item (takes real time) |
  | `place_block` | `x,y,z: Int`, `item: String` | select `item` and place it at the (empty) coords against a solid neighbor |
  | `interact_block` | `x,y,z: Int` | right-click a block (doors, buttons, levers, chests…) with held item |
  | `use_item` | `holdTicks: Int = 0` clamp 0..100 | right-click air with held item (eat/drink/throw/bow); 0 = until use finishes |
  | `attack` | `entityUuid: String` | melee attack an entity in reach |
  | `select_item` | `item: String` | hold the item in main hand (moves it to the hotbar if needed) |
  | `chat` | `message: String` (≤256 chars) | say something in public chat as this player |
  | `wait` | `seconds: Int = 1` clamp 1..30 | idle; implemented in core with `delay`, not on the body |

  Each tool clamps its args, then calls the matching `AgentBody` method. Each `Args` property has an `@property:LLMDescription`.
- **`core/.../llm/LlmService.kt`**, `class LlmService(config: LlmConfig) : AutoCloseable`:
  - **Executor:** build it with the thread context classloader set to `LlmService::class.java.classLoader`, then restore the previous loader in `finally`. Koog's `ServiceLoader.load(KoogHttpClient.Factory::class.java)` and Ktor's engine lookup use that classloader, and Paper's main-thread classloader cannot see the plugin jar.
    ```kotlin
    MultiLLMPromptExecutor(LLMProvider.OpenAI to OpenAILLMClient(apiKey = config.apiKey, settings = OpenAIClientSettings(baseUrl = config.baseUrl.trimEnd('/'), responsesAPIPath = "responses")))
    ```
  - **Model:** `val model = LLModel(provider = LLMProvider.OpenAI, id = config.model, capabilities = listOf(LLMCapability.Tools, LLMCapability.OpenAIEndpoint.Responses))`.
  - **Params:** `fun params() = OpenAIResponsesParams(parallelToolCalls = false)`.
  - **`suspend fun suggestName(task: String, rejected: List<String>): String?`**
    - Calls `executor.execute(prompt("agentcraft-name", params = params()) { system(NAME_PROMPT); user(task) }, model)`.
    - Takes `.textContent().trim()`, removes surrounding quotes/backticks, and keeps the first token split on whitespace.
    - Returns it only if it matches `^[A-Za-z0-9_]{3,16}$` and is not in `rejected` (ignore case). Otherwise returns null.
    - `NAME_PROMPT`: "Invent a Minecraft username for a bot that will perform the task below. Reply with ONLY the username: 3-16 characters, letters/digits/underscore only, no spaces, no explanation." When `rejected` is non-empty, append " Do not use: a, b, c".
  - `close()` closes the executor.
- **`core/.../llm/SystemPrompt.kt`**, `fun systemPrompt(id: Int, name: String): String`. The English text tells the model:
  - It is AgentCraft agent #id controlling the in-game player `name` on a Minecraft 26.3 server.
  - It acts only through tools, and coordinates are block coordinates (y is up).
  - It should observe first with `get_status`/`scan_area`/`find_blocks` before acting.
  - `walk_to` reaches at most 64 blocks per call; `break_block`/`place_block`/`attack`/`interact_block` need the target within ~4.5 blocks, so walk first.
  - User messages start with `[from <sender> …]`, and later messages may change the task.
  - When the task is done or impossible, reply with a short summary and **no tool calls**; the body will then stand still.
  - Reply in the language the user wrote in.
- **`core/.../llm/HistoryRepair.kt`**, `fun closeDanglingToolCalls(p: Prompt): Prompt`:
  - Collect the ids of every `MessagePart.Tool.Result` across all messages.
  - For the **last** `Message.Assistant`, find its `MessagePart.Tool.Call` parts whose `id` is not in that set.
  - If there are any, return `prompt(p) { user { dangling.forEach { toolResult(id = it.id, tool = it.tool, output = "Cancelled: the action was interrupted before it finished.", isError = true) } } }`. Otherwise return `p` unchanged.
  - Unit test `HistoryRepairTest`:
    - An assistant message with 2 calls and 1 result → exactly 1 synthetic error result is appended, and it carries the missing id.
    - A fully answered prompt → the same instance is returned.
- **`core/.../Agent.kt`**, `class Agent(val id: Int, var name: String?, var uuid: UUID?, history: List<Message>, log: AgentLog)`:
  - `enum class AgentState { SPAWNING, WORKING, IDLE }`.
  - Fields:
    - `@Volatile var state`
    - `var body: AgentBody?`
    - `var job: Job?`
    - `val inbox = ArrayDeque<String>()`
    - `@Volatile var history: List<Message>`, which never contains the system message
    - `@Volatile var destroyed = false`
    - `val lock = Any()`
  - `fun takeInboxOrFinish(): List<String>` runs under `lock`. It drains the inbox. If the inbox was empty, it sets `state = IDLE` before returning the empty list. This makes the "finish vs. new instruction" decision atomic with `chat`.
- **`core/.../llm/AgentRunner.kt`**, `suspend fun runAgent(agent: Agent, body: AgentBody, input: String, llm: LlmService, maxIterations: Int)`. Steps:
  1. Build the prompt: `prompt("agent-${agent.id}", params = llm.params()) { system(systemPrompt(agent.id, body.name)) }`, then `.withMessages { it + agent.history }`, then pass it through `closeDanglingToolCalls`. The system message is always regenerated and never persisted.
  2. Build the config: `AIAgentConfig(prompt = that, model = llm.model, maxAgentIterations = maxIterations)`.
  3. Build the agent: `AIAgent(promptExecutor = llm.executor, agentConfig = cfg, strategy = strategy, toolRegistry = agentTools(body, agent.log))`, then call `.run(input)`.
  4. Use `fun publish(p: Prompt) { agent.history = p.messages.filter { it !is Message.System } }`.

  The functional strategy:
  ```kotlin
  functionalStrategy<String, String>("agentcraft") { input ->
      llm.writeSession { appendPrompt { user(input) } }; publish(llm.readSession { prompt })
      var response = llm.writeSession { requestLLM() }; publish(llm.readSession { prompt })
      var requests = 1
      while (true) {
          response.textContent().takeIf { it.isNotBlank() }?.let { agent.log.add(ASSISTANT, it) }
          val calls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
          val results = if (calls.isEmpty()) emptyList() else executeTools(calls)
          val pending = if (calls.isEmpty()) agent.takeInboxOrFinish() else synchronized(agent.lock) { agent.inbox.toList().also { agent.inbox.clear() } }
          if (calls.isEmpty() && pending.isEmpty()) return@functionalStrategy response.textContent()
          if (requests >= maxIterations) { agent.log.add(EVENT, "Iteration limit ($maxIterations) reached"); agent.state = IDLE; return@functionalStrategy "" }
          llm.writeSession { appendPrompt {
              if (results.isNotEmpty()) user { results.forEach { toolResult(it.toMessagePart()) } }
              pending.forEach { user(it) }
          } }
          publish(llm.readSession { prompt })
          response = llm.writeSession { requestLLM() }; publish(llm.readSession { prompt }); requests++
      }
  }
  ```
  After the iteration limit, any instructions still in the inbox remain for the next run. `chat` adds to the inbox only while the agent is WORKING.
- **`core/.../AgentManager.kt`**, `class AgentManager(platform: Platform, config: LlmConfig)`:
  - Owns `scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("AgentCraft"))`.
  - Owns `llm: LlmService?`. It is created lazily on first use and only when `config.validate() == null`.
  - Owns `agents: ConcurrentHashMap<Int, Agent>` and `store = AgentStore(platform.dataDir.resolve("agents.json"))`.
  - Command-facing methods are called on the main thread and return the reply lines. Exact reply literals:
    - **`start(prompt: String, spawn: SpawnPoint, requester: String, requesterLocation: SpawnPoint?): String`**
      1. If config is invalid → `"AgentCraft is not configured: <problem> (plugins/AgentCraft/config.yml)"`.
      2. Allocate a random unused id in `1000..9999`. This means `IntegerArgumentType` never drops leading zeros.
      3. Register `Agent(state=SPAWNING)`.
      4. Log `USER` with the formatted input (see below).
      5. Launch the spawn job and return `"Agent #<id> created; choosing a name and spawning…"`.
      6. The spawn job:
         - **Naming:** try `suggestName` up to 3 times. Add each invalid or unavailable candidate to `rejected`; a candidate is unavailable when `platform.isNameAvailable(n)` is false or another agent already uses that name (ignore case). Any LLM exception goes straight to the fallback. The fallback name is `"Agent_<id>"`.
         - **Spawn:** `uuid = UUID.nameUUIDFromBytes("AgentCraft:<id>:<name>:<System.nanoTime()>".toByteArray())`, then `body = withContext(NonCancellable) { platform.spawnBody(BodySpec(uuid, name, spawn)) }`.
         - If the agent was destroyed in the meantime, run `platform.runOnMain { body.despawn(); platform.deleteBodyData(uuid) }` and return.
         - Otherwise `notifyOperators("Agent #<id> spawned as <name>")`, `saveAsync()`, and start the run with the original formatted input.
         - If spawning throws: log `ERROR`, remove the agent, and `notifyOperators("Agent #<id> failed to spawn: <msg>")`.
    - **`chat(id, prompt, requester, requesterLocation): String`**. Unknown id → `"No agent with id <id>"`. Otherwise, under `agent.lock`, check in this order:
      - SPAWNING or WORKING → append the formatted input to the inbox and log `USER`. Reply `"Instruction queued for agent #<id>"`.
      - IDLE with `body == null` (restore failed) → `"Agent #<id> has no body in the world"`.
      - IDLE with invalid config → the not-configured message.
      - IDLE otherwise → log `USER`, start a run, and reply `"Agent #<id> resumed"`.
    - **`list(): List<String>`**:
      - No agents → `["No agents."]`.
      - Otherwise, sorted by id: `"#<id> <name or '(naming)'> [<state>] <world> <x> <y> <z> — <last USER log text, first 40 chars>"`. Coordinates are integers from `body.location()`; show `offline` when the location is missing.
    - **`view(id): List<String>`**: a header `"Agent #<id> <name> [<state>]"`, then `log.last(10)` formatted as `"[HH:mm:ss] <KIND> <text first 300 chars, newlines→' '>"`, using local time.
    - **`stop(id, requester)`**:
      - SPAWNING → `"Agent #<id> is still spawning"`.
      - IDLE → `"Agent #<id> is already idle"`.
      - WORKING → under the lock: clear the inbox, set IDLE, `job.cancel()`, `body.halt()`, log `EVENT "Stopped by <requester>"`, then `saveAsync()`. Reply `"Agent #<id> stopped"`.
    - **`destroy(id, requester)`**:
      1. Set `destroyed = true`, cancel the job, remove the agent from the map.
      2. If a body exists: `body.despawn()`, then `platform.deleteBodyData(uuid)`.
      3. `saveAsync()`. Reply `"Agent #<id> destroyed"`.
  - **Input formatting:** `"[from <requester> at <world> <x> <y> <z>] <prompt>"` with integer coordinates. When there is no requester location (console), use `"[from <requester>] <prompt>"`.
  - **Run launcher** (private `startRun(agent, input)`, called under `agent.lock` or from the spawn job):
    - Set `agent.state = WORKING`. Create `val job = scope.launch(start = CoroutineStart.LAZY) { … }`, assign `agent.job = job`, then call `job.start()`. The lazy start guarantees the identity check below sees the assigned job.
    - The body is `runAgent(agent, body, input, llm, config.maxIterations)` inside a `try`. Touch `state` only if `agent.job === coroutineContext.job`, so a stale run never overwrites a newer one.
    - Normal return: state = IDLE (already set by `takeInboxOrFinish`), then `notifyOperators("Agent #<id> (<name>) finished: <final text first 200 chars>")`.
    - `CancellationException`: rethrow after the `finally`.
    - Other exception: log `ERROR e.message`, state = IDLE, `notifyOperators("Agent #<id> (<name>) failed: <msg first 200>")`.
    - `finally`: `body.halt()`, then `saveAsync()`.
  - **`saveAsync()`** = `platform.runOnMain { saveNow() }`. **`saveNow()`** runs on the main thread. It builds one `AgentRecord` for each agent that has a `uuid` and `name`, using `location = body?.location()`, and calls `store.save`. Store I/O is small, so doing it synchronously is fine.
  - **`restoreAll()`** runs on the main thread, once, one tick after enable. For each record:
    1. Register `Agent(state=IDLE, history=record.messages, log=AgentLog(record.log))`.
    2. Launch `platform.spawnBody(BodySpec(uuid, name, record.location))`.
    3. On failure: log `ERROR`, `platform.warn(...)`, and keep the record. The body stays null, and `list` shows `offline`.
  - **`onBodyEvent(uuid: UUID, text: String)`**: log `EVENT`. If the agent is WORKING, also add `"[event] $text"` to the inbox.
  - **`shutdown()`** runs on the main thread from `onDisable`:
    1. `saveNow()`.
    2. `scope.cancel()`.
    3. `despawn()` every body.
    4. `llm?.close()`.

  The save happens before cancelling so histories are complete. Dangling tool calls are repaired on the next run by `closeDanglingToolCalls`.

### 5. Bukkit platform: fake player and body control (NMS)

All of these files are under `platforms/bukkit/src/main/kotlin/io/github/mioyi/agentcraft/bukkit/`.

- **`MainThread.kt`**: `suspend fun <T> Plugin.onMain(block: () -> T): T`.
  - If `Bukkit.isPrimaryThread()`, run inline.
  - Otherwise use `suspendCancellableCoroutine` plus `Bukkit.getScheduler().runTask(this) { cont.resumeWith(runCatching(block)) }`. Check `cont.isActive` before resuming.
  - If `!isEnabled`, or `runTask` throws `IllegalPluginAccessException`, throw `CancellationException("plugin disabled")`.
- **`nms/FakeChannel.kt`**: write from the description; do not copy FakePlayer-CE, which is GPL. `class FakeChannel : io.netty.channel.AbstractChannel(null)`:
  - `config()` returns `DefaultChannelConfig(this)`.
  - `isOpen()` and `isActive()` return true.
  - `metadata()` returns `ChannelMetadata(true)`.
  - `localAddress0()` and `remoteAddress0()` return `InetSocketAddress(InetAddress.getLoopbackAddress(), 25565)`.
  - `doWrite(buf)` loops `while (buf.current() != null) buf.remove()`.
  - `doBind`, `doDisconnect`, `doClose` and `doBeginRead` do nothing.
  - `isCompatible(loop) = true`.
  - `newUnsafe()` returns an `AbstractUnsafe` whose `connect(...)` sets success on the promise.
  - `eventLoop()` returns the companion's `DefaultEventLoop()` singleton.
- **`nms/FakeConnection.kt`**: `class FakeConnection : net.minecraft.network.Connection(PacketFlow.SERVERBOUND)`.
  - The `init` block sets `channel = FakeChannel()` and `address = channel.remoteAddress()`, then calls `Connection.configureSerialization(channel.pipeline(), PacketFlow.SERVERBOUND, false, null)`.
  - Override `isConnected() = true`.
  - Override **every** `send(...)` overload that `Connection` declares in the 26.3 dev bundle as a no-op. Check them in the IDE or with `javap`.
- **`nms/AgentPacketListener.kt`**: `class AgentPacketListener(server, connection, player, cookie) : ServerGamePacketListenerImpl(server, connection, player, cookie)`. Override `send(packet: Packet<*>)`:
  - `ClientboundSetEntityMotionPacket` with `packet.id() == player.id && player.hurtMarked` → on the next tick, set `player.hurtMarked = true` and call `player.lerpMotion(packet.movement())`. This applies knockback.
  - `ClientboundRespawnPacket` → `player.hasChangedDimension()`.
  - Anything else → drop it.
- **`nms/FakePlayers.kt`**, `object FakePlayers`. All methods run on the main thread.
  - **`spawn(spec: BodySpec): Player`**:
    1. `server = (Bukkit.getServer() as CraftServer).server`.
    2. `level = (Bukkit.getWorlds()[0] as CraftWorld).handle`.
    3. `profile = GameProfile(spec.uuid, spec.name)`.
    4. `handle = ServerPlayer(server, level, profile, ClientInformation.createDefault())`.
    5. Load existing data via `playerIo.load(handle.nameAndId())` with `TagValueInput` (see Verified facts).
    6. `handle.bukkitEntity.isPersistent = true`.
    7. `connection = FakeConnection()`, `cookie = CommonListenerCookie.createInitial(profile, false)`.
    8. `server.playerList.placeNewPlayer(connection, handle, cookie)`.
    9. `handle.connection = AgentPacketListener(server, connection, handle, cookie)`.
    10. If `spec.spawn != null`, teleport the Bukkit player to it. Resolve the world with `Bukkit.getWorld(NamespacedKey.fromString(spawn.world)!!)`, falling back to the main world when the world is missing.
    11. Return `handle.bukkitEntity`.
  - **`remove(uuid)`**: if the `ServerPlayer` exists in `server.playerList.getPlayer(uuid)`, call `server.playerList.remove(it)`.
  - **`handle(uuid): ServerPlayer?`** = `(Bukkit.getPlayer(uuid) as? CraftPlayer)?.handle`. Re-resolve it every time, because respawn replaces the object.
- **`BukkitBlockView.kt`**: `BlockView` over a `World`. The cell mapping:
  - Chunk not loaded → AVOID.
  - Material is LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, SWEET_BERRY_BUSH, POWDER_SNOW, CAMPFIRE or SOUL_CAMPFIRE, or is in `Tag.FENCES`, `Tag.WALLS` or `Tag.FENCE_GATES` → AVOID.
  - `type == WATER` → WATER.
  - `block.isPassable` → PASSABLE.
  - Anything else → SOLID.
- **`BukkitAgentBody.kt`**, `class BukkitAgentBody(plugin, override val uuid, override val name) : AgentBody`. It holds a `@Volatile var action: BodyAction?`.
  - `sealed class BodyAction` subclasses:
    - `Walk(path, timeoutTicks, cont)`
    - `Break(pos, ticksNeeded, cont)`
    - `Hold(remaining, cont)`
    - `Jump(cont)`

    Each has `val cancelled = AtomicBoolean()`. Its continuation comes from `suspendCancellableCoroutine`, with `invokeOnCancellation { cancelled.set(true) }`.
  - Every method body runs in `plugin.onMain { … }`.
  - If `FakePlayers.handle(uuid)` is null, the method returns `"Error: your body is not in the world"`.
  - Starting a new long action completes any previous action with `"Error: interrupted by a new action"`.
  - **`tick(n: Long)`** is called every tick by the platform ticker:
    1. Resolve the handle; return if it is null or `isRemoved`.
    2. Advance the action, which sets `zza`/`xxa`/`setJumping`/rotation.
    3. `handle.doTick()`.
    4. If `n % 10 == 0`, call `handle.level().chunkSource.move(handle)`.
    5. When an action completes or is cancelled, reset `zza = 0f`, `xxa = 0f`, `setJumping(false)`.
  - **`halt()`** sets `action?.cancelled = true`.
  - Per-tool behavior. Status output formats are part of the contract, because the mock-server verification parses the `get_status` first line.
    - **`status`**: multi-line. The first line is exactly `"Position: world=<key> x=%.1f y=%.1f z=%.1f yaw=%.0f pitch=%.0f"`. Then lines: `Health: h/max`, `Food: f (saturation s)`, `Level: l`, `GameMode: SURVIVAL`, `Held: <material> x<count>` or `Held: nothing`, `World time: <ticks> (<day|night>)`, `Weather: <clear|rain|thunder>`, `Standing on: <material>`.
    - **`inventory`**: one line per non-empty slot, `"slot <i>: <material> x<count>[ (damage d/max)]"`. Add `"Selected hotbar slot: <i>"`, plus armor and offhand lines. If there is nothing, return `"Inventory is empty"`.
    - **`getBlock`**: `"<x> <y> <z>: <material> <blockData.asString>"`.
    - **`scanArea(r)`**: scan the cube around the feet block. Group non-air blocks by material and sort by count descending, giving `"<material> x<count>: nearest (x y z), …"` with up to 5 coordinates each.
    - **`findBlocks`**: `Material.matchMaterial(type)`. If it is null → `"Error: unknown block type <type>"`. Scan the cube within loaded chunks only, sort by distance, and output `"(x y z) dist=%.1f"` lines. If nothing is found → `"No <type> within <r> blocks"`.
    - **`nearbyEntities`**: `player.getNearbyEntities(r,r,r)` sorted by distance, at most 20 lines of `"<uuid> <type> \"<name>\" (x y z) dist=%.1f[ health=h]"`.
    - **`walkTo`**:
      1. Reject a goal with horizontal distance > 64 → `"Error: target is N blocks away; walk_to supports at most 64 per call"`.
      2. Start position = the feet block `(loc.blockX, floor(loc.y + 0.01), loc.blockZ)`. Run `PathFinder(BukkitBlockView(world)).find(start, goal, within)` on the main thread.
      3. `null` → `"Error: no path found to x y z"`. Empty list → `"Already there"`.
      4. Otherwise run a `Walk` action. Each tick:
         - Waypoint target is the block centre `(x+0.5, z+0.5)`.
         - Advance when the horizontal distance < 0.35 and `|player.y - wp.y| < 1.2`.
         - Set yaw = `Math.toDegrees(atan2(-dx, dz))` via `setYRot`/`setYHeadRot`/`setYBodyRot`, and set `zza = 1f`.
         - Set jumping when `wp.y > feetY`, or when `horizontalCollision && onGround()`, or when `isInWater` and `wp.y >= feetY`.
         - **Stuck detection:** track the best distance to the current waypoint. If it has not improved by ≥ 0.05 over 30 ticks, replan from the current feet block. After 2 replans, complete with `"Error: stuck at x y z after <i>/<n> waypoints"`.
         - **Timeout** = `20 * (path.size + 10)` ticks → `"Error: timed out at x y z"`.
         - **Success**: `"Arrived at x y z"`, using the actual position.
    - **`lookAt`**: compute yaw/pitch from the eye location to the point and apply them through NMS `setYRot`/`setXRot`/`setYHeadRot`. Return `"Looking at x y z"`.
    - **`jump`**: run a `Jump` action. Set `setJumping(true)` for 1 tick, then complete after 10 ticks with `"Jumped"`. If not on the ground → `"Error: not on the ground"`.
    - **Reach checks** use `player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE)!!.value + 0.5` (eye to block centre) and `Attribute.ENTITY_INTERACTION_RANGE` (eye to the closest point of the entity's bounding box). Out of reach → `"Error: target is %.1f blocks away (reach %.1f); walk closer first"`.
    - **`breakBlock`**:
      - Air → `"Error: nothing to break at x y z"`.
      - In creative → `player.breakBlock(block)` right away.
      - Otherwise `speed = block.getBreakSpeed(player)`. If `speed <= 0` → `"Error: <material> cannot be broken"`. Set `ticksNeeded = ceil(1 / speed)`; if it is > 600 → `"Error: would take <s>s with current tool; select a better tool"`.
      - Run a `Break` action. Each tick, look at the block centre and call `player.swingMainHand()` every 4 ticks. Abort with an error if the block type changes, or if it leaves reach.
      - When done, `player.breakBlock(block)`. Success → `"Broke <material> at x y z"`. `false` → `"Error: breaking was denied"`.
      - Drops are picked up naturally by the ticked player.
    - **`placeBlock`**:
      - Run `selectItem(item)` first, and return its error if it fails.
      - Target must be `isReplaceable` → otherwise `"Error: x y z is occupied by <material>"`.
      - Target must not intersect the player's bounding box → `"Error: you are standing in that space"`.
      - Find a support face by iterating `Direction.entries` with DOWN first. The neighbour is `pos.relative(dir)`; it qualifies when its state is not air and not replaceable.
      - None found → `"Error: no solid block next to x y z to place against"`.
      - Otherwise build `hit = BlockHitResult(Vec3.atCenterOf(pos).add(dir.x*0.5, dir.y*0.5, dir.z*0.5), dir.opposite, neighbourPos, false)`, then call `handle.gameMode.useItemOn(handle, handle.level(), handle.mainHandItem, InteractionHand.MAIN_HAND, hit)` and `player.swingMainHand()`.
      - Success when the block at the target is no longer replaceable → `"Placed <material> at x y z"`. Otherwise `"Error: placement failed (<result>)"`.
    - **`interactBlock`**: reach check, then `face = Direction.getApproximateNearest(eye - centre)`. Use the same kind of `useItemOn` with `BlockHitResult(Vec3.atCenterOf(pos).add(face*0.5), face, pos, false)`, and swing. Return `"Interacted with <material> at x y z: <result>; block is now <blockData.asString>"`.
    - **`useItem(hold)`**:
      - `handle.gameMode.useItem(handle, handle.level(), handle.mainHandItem, InteractionHand.MAIN_HAND)`.
      - If `handle.isUsingItem`, run a `Hold` action until `!isUsingItem`. The limit is `hold` ticks, or 100 when `hold == 0`. When the limit is hit, call `handle.releaseUsingItem()`.
      - Return `"Used <material>: <result>; now holding <material> x<count>"`.
    - **`attack`**:
      - Parse the UUID → on error `"Error: invalid uuid"`.
      - `Bukkit.getEntity(uuid)`, in the same world → otherwise `"Error: entity not found"`.
      - Reach check, look at the entity, `player.attack(entity)`, `player.swingMainHand()`.
      - Return `"Attacked <type>; its health is now h"` for living targets.
    - **`selectItem`**:
      - `Material.matchMaterial(item)`. If null → `"Error: unknown item <item>"`.
      - Hotbar slots 0–8 → set `heldItemSlot`.
      - Slots 9–35 → swap with the held slot.
      - Neither → `"Error: no <material> in inventory"`.
      - Return `"Holding <material> x<count>"`.
    - **`chat`**: `player.chat(message.take(256))`, then `"Said: <message>"`.
    - **`location()`**: `SpawnPoint(world.key.asString(), x, y, z, yaw, pitch)`.
    - **`despawn()`**: complete the action as cancelled, then `FakePlayers.remove(uuid)`.
- **`BukkitPlatform.kt`** implements `Platform`:
  - `dataDir = plugin.dataFolder.toPath()`.
  - `log`/`warn` go to `plugin.logger`.
  - `notifyOperators` sends `Component.text("[AgentCraft] ", NamedTextColor.AQUA).append(Component.text(msg))` to online players with `isOp`, and logs the message.
  - `runOnMain` is `if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin, Runnable(task))`.
  - `isNameAvailable(n)` = `onMain { Bukkit.getPlayerExact(n) == null && Bukkit.getOfflinePlayerIfCached(n) == null }`.
  - `spawnBody(spec)`:
    1. `onMain { FakePlayers.spawn(spec) }`.
    2. Create a `BukkitAgentBody`.
    3. Register it in `bodies: ConcurrentHashMap<UUID, BukkitAgentBody>`.
    4. Return it.
  - `deleteBodyData(uuid)`: walk `Bukkit.getWorlds()[0].worldFolder.toPath()` with `Files.walk(root, 4)` and delete regular files whose file name starts with `uuid.toString()`. That covers `.dat`, `.dat_old`, stats and advancements regardless of the 26.x folder layout. Log each deletion.
  - A single global ticker, `runTaskTimer(plugin, 1L, 1L)`, increments a counter and calls `body.tick(counter)` for every registered body. A body is unregistered on `despawn`.
- **`BodyListener.kt`** (Bukkit `Listener`):
  - `PlayerDeathEvent` where `player.uniqueId` belongs to a registered body:
    1. Complete the current action with `"Error: you died"`.
    2. `runTaskLater(plugin, 1L) { player.spigot().respawn() }`.
    3. `manager.onBodyEvent(uuid, "You died (<plain death message>) and respawned at x y z")`. Read the position in the delayed task, after respawn.
  - Players are never cached; bodies always re-resolve by UUID.

### 6. Bukkit plugin entry, config, commands

- `platforms/bukkit/src/main/resources/paper-plugin.yml`:
  ```yaml
  name: AgentCraft
  version: '${version}'
  main: io.github.mioyi.agentcraft.bukkit.AgentCraftPlugin
  api-version: '26.3'
  description: LLM agents that control fake players
  ```
  A Paper plugin, not `plugin.yml`, gets an isolated classloader. That way the bundled Kotlin, Ktor and Koog cannot clash with other plugins without relocating anything.
- `config.yml` (default resource):
  ```yaml
  llm:
    # OpenAI Responses API. Base URL includes the version prefix, e.g. https://api.openai.com/v1 (requests go to <base-url>/responses)
    api-key: ""
    base-url: "https://api.openai.com/v1"
    model: "gpt-5"
  agent:
    # max LLM requests per run before the agent is forced idle
    max-iterations: 100
  ```
- **`AgentCraftPlugin.kt`** (`JavaPlugin`):
  - **`onEnable`:**
    1. `saveDefaultConfig()`.
    2. Build `LlmConfig` from the config keys.
    3. If `validate()` reports a problem, log a warning `"LLM not configured: <problem>"`; the plugin still enables.
    4. Create `BukkitPlatform` and `AgentManager`.
    5. Register `BodyListener`.
    6. Start the ticker.
    7. Register commands through `lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { it.registrar().register(AgentCommands(manager, this).build(), "Control LLM agents") }`.
    8. `server.scheduler.runTask(this) { manager.restoreAll() }`.
  - **`onDisable`:** `manager.shutdown()`.
- **`AgentCommands.kt`**, built with Brigadier (`io.papermc.paper.command.brigadier.Commands`):
  - `literal("agent").requires { it.sender.isOp }`, with children:
    - `start` → `argument("prompt", StringArgumentType.greedyString())`
    - `chat` → `argument("id", IntegerArgumentType.integer(1000, 9999))` → `argument("prompt", greedyString())`
    - `list`
    - `view <id>`
    - `stop <id>`
    - `destroy <id>`
  - `<id>` arguments suggest the existing ids from `manager.ids()`.
  - **Sender details:**
    - Requester name: `sender.name`; the console name is `"CONSOLE"`.
    - Requester location: the location of `source.executor` or `sender` when that is a `Player`, otherwise null.
    - Spawn point for `start`: the requester location, otherwise the main world spawn location.
  - **Replies:** send every returned line to `source.sender` as `Component.text(line)`. Return `Command.SINGLE_SUCCESS`.

## Critical files & anchors

- `platforms/bukkit/.../nms/FakePlayers.kt` (`spawn`). This is the most version-sensitive part. Reference: FakePlayer-CE @7d064c0, `fakeplayer-v26_2/.../spi/NMSServerImpl.java` (`newPlayer`) and `spi/NMSNetworkImpl.java` (`placeNewPlayer`). Read them for behavior; do not copy them (GPL).
- `platforms/bukkit/.../BukkitAgentBody.kt` (`tick`, `walkTo`). It enforces the ordering "set inputs → `doTick()`". Reference: Carpet `src/main/java/carpet/helpers/EntityPlayerActionPack.java` and `patches/EntityPlayerMPFake.java#tick`.
- `core/.../llm/AgentRunner.kt` (the strategy loop). Inbox draining, `takeInboxOrFinish` atomicity, and `publish` after every prompt mutation must match step 4 exactly.
- `core/.../llm/LlmService.kt`. This holds the TCCL swap around client construction and the `OpenAIResponsesParams` + `OpenAIEndpoint.Responses` pairing that forces the Responses endpoint.
- `platforms/bukkit/build.gradle.kts`. This holds the service-file merging (`KoogHttpClient$Factory`, Ktor engine) and the rule of no reobf / no relocation.

## Verification

All commands run from the repo root with local `gradle`, no wrapper, on JDK 25.

1. **Build and unit tests:** `gradle build`. Expect `AgentStoreTest`, `PathFinderTest` and `HistoryRepairTest` to pass, and `platforms/bukkit/build/libs/AgentCraft-0.1.0.jar` to exist.
2. **Packaging check:** `unzip -p platforms/bukkit/build/libs/AgentCraft-0.1.0.jar 'META-INF/services/ai.koog.http.client.KoogHttpClient$Factory'` must print exactly one provider line. `unzip -l … | grep -c 'org/bukkit/'` must print `0`.
3. **Mock Responses server** (a throwaway, not committed). Write `/tmp/mock_responses.py`, a stdlib `http.server` that listens on `127.0.0.1:8089` and answers `POST /v1/responses`:
   - Request with no `tools` (naming) → an assistant message with output text `"Mock_Walker"`.
   - Request with tools and no `function_call_output` input items → `function_call` `get_status` with args `{}`.
   - Request whose last `function_call_output` is from `get_status` → parse `x=(-?[\d.]+) y=(-?[\d.]+) z=(-?[\d.]+)` and return `function_call` `walk_to` `{"x":<x+5>,"y":<y>,"z":<z>,"within":0.5}`.
   - Otherwise → message `"Done walking."`.
   - Shape the responses the way Koog's `OpenAIResponsesAPIResponse` expects: `id`, `object:"response"`, `created_at`, `model`, `status:"completed"`, `output:[…]`, `usage`. For the exact required fields, read `OpenAIResponsesAPI.kt` in the `prompt-executor-openai-client-jvm-1.3.0-sources.jar` that Gradle caches. Function calls look like `{"type":"function_call","id":"fc_1","call_id":"call_1","name":"walk_to","arguments":"{…}","status":"completed"}`. Messages look like `{"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"…","annotations":[]}]}`.
4. **Dev server:**
   1. Write `platforms/bukkit/run/plugins/AgentCraft/config.yml` with `api-key: "test"`, `base-url: "http://127.0.0.1:8089/v1"`.
   2. Run `gradle :platforms:bukkit:runServer` as a named bash service (pty) with ready log regex `Done \(`. If startup stops on the EULA, write `eula=true` to `platforms/bukkit/run/eula.txt` and restart.
   3. Send commands to the service's stdin (the console is op).
   - `agent start walk east` → the reply contains `Agent #NNNN created`; then the console shows `Agent #NNNN spawned as Mock_Walker` and `Mock_Walker joined the game`.
   - Within about 10 s: `agent view NNNN` shows `TOOL_CALL get_status`, `TOOL_CALL walk_to …`, `TOOL_RESULT Arrived at …`, `ASSISTANT Done walking.`. `agent list` shows `[IDLE]` with x about 5 greater than the spawn x, which proves movement through `zza` + `doTick`.
   - `agent chat NNNN again` → `Agent #NNNN resumed`. The view shows a second cycle, and the mock log shows that the second request's `input` contains the earlier `function_call` and `function_call_output` items. That proves history is carried between runs.
   - `agent stop NNNN` while it is working (send it right after `chat`) → `Agent #NNNN stopped`. The body stops moving, and a later `agent chat` succeeds: the mock gets no 400 from dangling calls, and the request has a synthetic `Cancelled:` output.
   - `kill Mock_Walker` → the view gets an `EVENT You died …` entry, and the player respawns (it shows up in `list`).
   - `stop` the server, start it again → `agent list` shows `#NNNN Mock_Walker [IDLE]` at the saved position, and `agent view NNNN` still shows the earlier log.
   - `agent destroy NNNN` → `Agent #NNNN destroyed`, `Mock_Walker left the game`, files starting with the agent's UUID are gone under `platforms/bukkit/run/world`, and `agent list` → `No agents.`.
   - Permission check: `deop` any test player and confirm that `/agent` is unknown for them. The `.requires` filter hides it. With no player client available, check `agent` from a non-op command block instead; it is not run in this plan, so only report it if it was actually exercised.
5. **Optional real LLM** (only if the user supplies a key): set the real `api-key`/`base-url`, then run `agent start dig the block under you and say what it was` and check that `break_block` and `chat` appear in `view`.

## Assumptions & contingencies

- **No relocation, contrary to the "relocate" option the user picked.** Relocating `kotlin.*` or `kotlinx.*` breaks Kotlin metadata, and Shadow also rewrites string constants. kotlinx-serialization serial names (for example `kotlin.String`), which Koog's tool-schema generator depends on, would be corrupted. Paper-plugin classloader isolation already prevents conflicts with other Kotlin plugins. If the user insists, relocate only `io.ktor` → `io.github.mioyi.agentcraft.libs.ktor`, and never `kotlin`/`kotlinx`.
- **Default model `gpt-5`** in `config.yml`. The user can change it; the plugin does not validate it against the provider.
- **AI-chosen name fallback:** after 3 invalid or unavailable suggestions, or on an LLM error, the name is `Agent_<id>`.
- **Destroy discards the body's inventory** (no item drop) and deletes its playerdata, stats and advancements.
- **Name collisions:** a real player who later logs in with a fake player's name is not handled. Paper deduplicates by UUID only.
- **Contingency — API version:** if Paper rejects `api-version: '26.3'`, use the value the server's error message suggests, or the one in the Paper docs `paper-plugin.yml` page.
- **Contingency — dev bundle changes:** if `CommonListenerCookie.createInitial(profile, false)`, `ServerPlayer.nameAndId()`, `playerIo`, the `TagValueInput.create` signature, or the public `Connection.channel`/`address` fields differ in the 26.3 dev bundle, adapt to the 26.3 signatures you find with `javap` against the dev-bundle jar in `~/.gradle/caches`. Behavior must stay the same.
- **Contingency — `FakeChannel` fails:** if `configureSerialization` or placement throws because of `FakeChannel`, replace `FakeChannel` with `io.netty.channel.embedded.EmbeddedChannel()`, and override every `setupInboundProtocol`/`setupOutboundProtocol` method in `FakeConnection` as a no-op.
- **Contingency — walking does not move the body:** if verification step 4 shows no position change after `walk_to` while `zza = 1f` is set before `doTick()`, replace it in `Walk`. Each tick, set `handle.deltaMovement = Vec3(dirX * 0.2, handle.deltaMovement.y, dirZ * 0.2)` before `doTick()`, and when jumping, call `handle.jumpFromGround()` if `onGround()`.
- **Contingency — Paper API names:** if `Block.getBreakSpeed(Player)`, `Player.breakBlock(Block)`, `LivingEntity.attack(Entity)` or `Bukkit.getOfflinePlayerIfCached` are missing in 26.3, use these NMS equivalents:
  - `state.getDestroyProgress(handle, level, pos)`
  - `handle.gameMode.destroyBlock(pos)`
  - `handle.attack(nmsEntity)`
  - `server.services().profileCache()` lookup (or skip the cache check)
- **Contingency — missing capability:** if Koog rejects the model for a missing capability, such as `Completion`, add that `LLMCapability` to the list in `LlmService.model`. Keep `OpenAIEndpoint.Responses` and never add `OpenAIEndpoint.Completions`, so the Responses endpoint stays forced.
