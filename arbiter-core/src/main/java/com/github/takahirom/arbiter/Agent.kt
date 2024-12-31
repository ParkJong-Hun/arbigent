package com.github.takahirom.arbiter

import com.github.takahirom.arbiter.Agent.*
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import maestro.MaestroException
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TakeScreenshotCommand
import kotlin.time.Duration.Companion.milliseconds

class Agent(
  agentConfig: AgentConfig
) {
  private val ai = agentConfig.ai
  private val device = agentConfig.device
  private val interceptors = agentConfig.interceptors
  private val deviceFormFactor = agentConfig.deviceFormFactor

  private val decisionInterceptors: List<ArbiterDecisionInterceptor> = interceptors
    .filterIsInstance<ArbiterDecisionInterceptor>()
  private val decisionChain: (ArbiterAi.DecisionInput) -> ArbiterAi.DecisionOutput =
    decisionInterceptors.foldRight(
      { input: ArbiterAi.DecisionInput -> ai.decideWhatToDo(input) },
      { interceptor, acc ->
        { input ->
          interceptor.intercept(input) { decisionInput -> acc(decisionInput) }
        }
      }
    )
  private val executeCommandsInterceptors: List<ArbiterExecuteCommandsInterceptor> = interceptors
    .filterIsInstance<ArbiterExecuteCommandsInterceptor>()
  private val executeCommandChain: (ExecuteCommandsInput) -> ExecuteCommandsOutput =
    executeCommandsInterceptors.foldRight(
      { input: ExecuteCommandsInput -> executeCommands(input) },
      { interceptor: ArbiterExecuteCommandsInterceptor, acc: (ExecuteCommandsInput) -> ExecuteCommandsOutput ->
        { input ->
          interceptor.intercept(input) { executeCommandsInput -> acc(executeCommandsInput) }
        }
      }
    )
  private val initializerInterceptors: List<ArbiterInitializerInterceptor> = interceptors
    .filterIsInstance<ArbiterInitializerInterceptor>()
  private val initializerChain: (ArbiterDevice) -> Unit = initializerInterceptors.foldRight(
    { device: ArbiterDevice -> initialize(device) },
    { interceptor, acc ->
      { device ->
        interceptor.intercept(device) { device -> acc(device) }
      }
    }
  )
  private val stepInterceptors: List<ArbiterStepInterceptor> = interceptors
    .filterIsInstance<ArbiterStepInterceptor>()
  private val stepChain: (StepInput) -> StepResult = stepInterceptors.foldRight(
    { input: StepInput -> step(input) },
    { interceptor, acc ->
      { input ->
        interceptor.intercept(input) { stepInput -> acc(stepInput) }
      }
    }
  )
  private val coroutineScope =
    CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob())
  private var job: Job? = null
  private val arbiterContextHistoryStateFlow: MutableStateFlow<List<ArbiterContextHolder>> =
    MutableStateFlow(listOf())
  val latestArbiterContextStateFlow: StateFlow<ArbiterContextHolder?> =
    arbiterContextHistoryStateFlow
      .map { it.lastOrNull() }
      .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), null)
  private val arbiterContextHolderStateFlow: MutableStateFlow<ArbiterContextHolder?> =
    MutableStateFlow(null)
  private val _isRunningStateFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
  val isRunningStateFlow: StateFlow<Boolean> = _isRunningStateFlow.asStateFlow()
  private val currentGoalStateFlow = MutableStateFlow<String?>(null)
  val isArchivedStateFlow = arbiterContextHolderStateFlow
    .flatMapLatest {
      it?.steps ?: flowOf()
    }
    .map { steps: List<ArbiterContextHolder.Step> ->
      steps.any { it.agentCommand is GoalAchievedAgentCommand }
    }
    .stateIn(coroutineScope, SharingStarted.Lazily, false)

  suspend fun waitUntilFinished() {
    isRunningStateFlow.first { !it }
  }

  fun executeAsync(
    goal: String,
    maxTurn: Int = 10,
    maxRetry: Int = 1,
    agentCommandTypes: List<AgentCommandType> = when (deviceFormFactor) {
      ArbiterScenarioDeviceFormFactor.Mobile -> defaultAgentCommandTypes()
      ArbiterScenarioDeviceFormFactor.Tv -> defaultAgentCommandTypesForTv()
    }
  ) {
    job?.cancel()
    job = coroutineScope.launch {
      var remainRetry = maxRetry
      do {
        execute(goal, maxTurn, agentCommandTypes)
        yield()
        try {
          withTimeout(100.milliseconds) {
            isArchivedStateFlow.first { it }
          }
        } catch (e: TimeoutCancellationException) {
        }
      } while (isArchivedStateFlow.value.not() && remainRetry-- > 0)
    }
  }

  suspend fun execute(
    goal: String,
    maxStep: Int = 10,
    agentCommandTypes: List<AgentCommandType> = defaultAgentCommandTypes()
  ) {
    println("Arbiter.execute agent.execute start $goal")
    try {
      _isRunningStateFlow.value = true
      currentGoalStateFlow.value = goal
      val arbiterContextHolder = ArbiterContextHolder(goal)
      println("Setting new ArbiterContextHolder: $arbiterContextHolder")
      arbiterContextHolderStateFlow.value = arbiterContextHolder
      arbiterContextHistoryStateFlow.value += arbiterContextHolder

      initializerChain(device)
      var stepRemain = maxStep
      while (stepRemain-- > 0 && isArchivedStateFlow.value.not()) {
        val stepInput = StepInput(
          arbiterContextHolder = arbiterContextHolder,
          agentCommandTypes = agentCommandTypes,
          device = device,
          deviceFormFactor = deviceFormFactor,
          ai = ai,
          decisionChain = decisionChain,
          executeCommandChain = executeCommandChain
        )
        when (stepChain(stepInput)) {
          StepResult.GoalAchieved -> {
            _isRunningStateFlow.value = false
            isArchivedStateFlow.first { it }
            break
          }

          StepResult.Continue -> {
            // continue
            delay(1000)
          }
        }
      }
      _isRunningStateFlow.value = false
    } catch (e: Exception) {
      println("Failed to run agent: $e")
      e.printStackTrace()
      _isRunningStateFlow.value = false
    } finally {
      println("Arbiter.execute agent.execute end $goal")
    }
  }

  data class StepInput(
    val arbiterContextHolder: ArbiterContextHolder,
    val agentCommandTypes: List<AgentCommandType>,
    val device: ArbiterDevice,
    val deviceFormFactor: ArbiterScenarioDeviceFormFactor,
    val ai: ArbiterAi,
    val decisionChain: (ArbiterAi.DecisionInput) -> ArbiterAi.DecisionOutput,
    val executeCommandChain: (ExecuteCommandsInput) -> ExecuteCommandsOutput,
  )

  sealed interface StepResult {
    object GoalAchieved : StepResult
    object Continue : StepResult
  }

  data class ExecuteCommandsInput(
    val decisionOutput: ArbiterAi.DecisionOutput,
    val arbiterContextHolder: ArbiterContextHolder,
    val screenshotFileName: String,
    val device: ArbiterDevice,
  )

  class ExecuteCommandsOutput


  fun cancel() {
    job?.cancel()
    _isRunningStateFlow.value = false
  }
}

class AgentConfig(
  val interceptors: List<ArbiterInterceptor>,
  val ai: ArbiterAi,
  val device: ArbiterDevice,
  val deviceFormFactor: ArbiterScenarioDeviceFormFactor
) {
  class Builder {
    private val interceptors = mutableListOf<ArbiterInterceptor>()
    private var device: ArbiterDevice? = null
    private var ai: ArbiterAi? = null
    private var deviceFormFactor: ArbiterScenarioDeviceFormFactor = ArbiterScenarioDeviceFormFactor.Mobile

    fun addInterceptor(interceptor: ArbiterInterceptor) {
      interceptors.add(interceptor)
    }

    fun device(device: ArbiterDevice) {
      this.device = device
    }

    fun ai(ai: ArbiterAi) {
      this.ai = ai
    }

    fun deviceFormFactor(deviceFormFactor: ArbiterScenarioDeviceFormFactor) {
      this.deviceFormFactor = deviceFormFactor
    }

    fun build(): AgentConfig {
      return AgentConfig(
        interceptors = interceptors,
        ai = ai!!,
        device = device!!,
        deviceFormFactor = deviceFormFactor
      )
    }
  }

  fun toBuilder(): Builder {
    val builder = Builder()
    interceptors.forEach {
      builder.addInterceptor(it)
    }
    builder.device(device)
    builder.ai(ai)
    return builder
  }
}

fun AgentConfig(block: AgentConfig.Builder.() -> Unit = {}): AgentConfig {
  val builder = AgentConfig.Builder()
  builder.block()
  return builder.build()
}


fun agentConfigBuilder(
  deviceFormFactor: ArbiterScenarioDeviceFormFactor,
  initializeMethods: ArbiterProjectSerializer.InitializeMethods,
  cleanupData: ArbiterProjectSerializer.CleanupData
) = AgentConfig {
  deviceFormFactor(deviceFormFactor)
  when (val method = initializeMethods) {
    ArbiterProjectSerializer.InitializeMethods.Back -> {
      // default
    }

    ArbiterProjectSerializer.InitializeMethods.Noop -> {
      addInterceptor(object : ArbiterInitializerInterceptor {
        override fun intercept(
          device: ArbiterDevice,
          chain: ArbiterInitializerInterceptor.Chain
        ) {
          // do nothing
        }
      })
    }

    is ArbiterProjectSerializer.InitializeMethods.OpenApp -> {
      addInterceptor(object : ArbiterInitializerInterceptor {
        override fun intercept(
          device: ArbiterDevice,
          chain: ArbiterInitializerInterceptor.Chain
        ) {
          device.executeCommands(
            listOf(
              MaestroCommand(
                launchAppCommand = LaunchAppCommand(
                  appId = method.packageName
                )
              )
            )
          )
        }
      })
    }
  }
  when (val cleanupData = cleanupData) {
    ArbiterProjectSerializer.CleanupData.Noop -> {
      // default
    }

    is ArbiterProjectSerializer.CleanupData.Cleanup -> {
      addInterceptor(object : ArbiterInitializerInterceptor {
        override fun intercept(
          device: ArbiterDevice,
          chain: ArbiterInitializerInterceptor.Chain
        ) {
          device.executeCommands(
            listOf(
              MaestroCommand(
                clearStateCommand = ClearStateCommand(
                  appId = cleanupData.packageName
                )
              )
            )
          )
          chain.proceed(device)
        }
      })
    }
  }
}.toBuilder()

interface ArbiterInterceptor

interface ArbiterInitializerInterceptor : ArbiterInterceptor {
  fun intercept(device: ArbiterDevice, chain: Chain)
  fun interface Chain {
    fun proceed(device: ArbiterDevice)
  }
}

interface ArbiterDecisionInterceptor : ArbiterInterceptor {
  fun intercept(decisionInput: ArbiterAi.DecisionInput, chain: Chain): ArbiterAi.DecisionOutput

  fun interface Chain {
    fun proceed(decisionInput: ArbiterAi.DecisionInput): ArbiterAi.DecisionOutput
  }
}

interface ArbiterExecuteCommandsInterceptor : ArbiterInterceptor {
  fun intercept(executeCommandsInput: ExecuteCommandsInput, chain: Chain): ExecuteCommandsOutput
  fun interface Chain {
    fun proceed(executeCommandsInput: ExecuteCommandsInput): ExecuteCommandsOutput
  }
}

interface ArbiterStepInterceptor : ArbiterInterceptor {
  fun intercept(stepInput: StepInput, chain: Chain): StepResult
  fun interface Chain {
    fun proceed(stepInput: StepInput): StepResult
  }
}

fun defaultAgentCommandTypes(): List<AgentCommandType> {
  return listOf(
    ClickWithIdAgentCommand,
    ClickWithTextAgentCommand,
    InputTextAgentCommand,
    BackPressAgentCommand,
    KeyPressAgentCommand,
    ScrollAgentCommand,
    GoalAchievedAgentCommand
  )
}

fun defaultAgentCommandTypesForTv(): List<AgentCommandType> {
  return listOf(
    DpadUpArrowAgentCommand,
    DpadDownArrowAgentCommand,
    DpadLeftArrowAgentCommand,
    DpadRightArrowAgentCommand,
    DpadCenterAgentCommand,
    InputTextAgentCommand,
    BackPressAgentCommand,
    KeyPressAgentCommand,
    GoalAchievedAgentCommand
  )
}

private fun initialize(device: ArbiterDevice) {
  repeat(10) {
    try {
      device.executeCommands(
        commands = listOf(
          MaestroCommand(
            backPressCommand = BackPressCommand()
          )
        ),
      )
    } catch (e: Exception) {
      println("Failed to back press: $e")
    }
  }
}


private fun executeCommands(
  executeCommandsInput: ExecuteCommandsInput,
): ExecuteCommandsOutput {
  val (decisionOutput, arbiterContextHolder, screenshotFileName, device) = executeCommandsInput
  decisionOutput.agentCommands.forEach { agentCommand ->
    try {
      agentCommand.runOrchestraCommand(device)
    } catch (e: MaestroException) {
      e.printStackTrace()
      arbiterContextHolder.addStep(
        ArbiterContextHolder.Step(
          memo = "Failed to perform action: ${e.message}. Please try other actions.",
          screenshotFileName = screenshotFileName
        )
      )
    } catch (e: StatusRuntimeException) {
      e.printStackTrace()
      arbiterContextHolder.addStep(
        ArbiterContextHolder.Step(
          memo = "Failed to perform action: ${e.message}. Please try other actions.",
          screenshotFileName = screenshotFileName
        )
      )
    } catch (e: IllegalStateException) {
      e.printStackTrace()
      arbiterContextHolder.addStep(
        ArbiterContextHolder.Step(
          memo = "Failed to perform action: ${e.message}. Please try other actions.",
          screenshotFileName = screenshotFileName
        )
      )
    }
  }
  return ExecuteCommandsOutput()
}

private fun step(
  stepInput: StepInput
): StepResult {
  val (arbiterContextHolder, agentCommandTypes, device, deviceFormFactor, ai, decisionChain, executeCommandChain) = stepInput
  val screenshotFileName = System.currentTimeMillis().toString()
  try {
    device.executeCommands(
      commands = listOf(
        MaestroCommand(
          takeScreenshotCommand = TakeScreenshotCommand(
            screenshotFileName
          )
        ),
      ),
    )
  } catch (e: Exception) {
    println("Failed to take screenshot: $e")
  }
  println("Arbiter step(): ${arbiterContextHolder.prompt()}")
  val decisionInput = ArbiterAi.DecisionInput(
    arbiterContextHolder = arbiterContextHolder,
    dumpHierarchy = device.viewTreeString(),
    focusedTreeString = if(deviceFormFactor.isTv()){
      // It is important to get focused tree string for TV form factor
      device.focusedTreeString()
    } else {
      null
    },
    agentCommandTypes = agentCommandTypes,
    screenshotFileName = screenshotFileName
  )
  val decisionOutput = decisionChain(decisionInput)
  arbiterContextHolder.addStep(decisionOutput.step)
  if (decisionOutput.agentCommands.size == 1 && decisionOutput.agentCommands.first() is GoalAchievedAgentCommand) {
    println("Goal achieved")
    return StepResult.GoalAchieved
  }
  executeCommandChain(
    ExecuteCommandsInput(
      decisionOutput = decisionOutput,
      arbiterContextHolder = arbiterContextHolder,
      screenshotFileName = screenshotFileName,
      device = device
    )
  )
  return StepResult.Continue
}
