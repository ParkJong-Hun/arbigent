package com.github.takahirom.arbiter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class RunningInfo(
  val allTasks: Int,
  val runningTasks: Int,
  val retriedTasks: Int,
  val maxRetry: Int,
) {
  override fun toString(): String {
    return """
        task:$runningTasks/$allTasks
        retry:$retriedTasks/$maxRetry
    """.trimIndent()
  }
}

class Arbiter {
  data class Task(
    val goal: String,
    val agentConfig: AgentConfig,
  )

  data class Scenario(
    val tasks: List<Task>,
    val retry: Int = 0,
  )

  private val _taskToAgentStateFlow = MutableStateFlow<List<Pair<Task, Agent>>>(listOf())
  val taskToAgentStateFlow: StateFlow<List<Pair<Task, Agent>>> = _taskToAgentStateFlow.asStateFlow()
  private var executeJob: Job? = null
  private val coroutineScope =
    CoroutineScope(ArbiterCorotuinesDispatcher.dispatcher + SupervisorJob())
  private val _runningInfoStateFlow: MutableStateFlow<RunningInfo?> = MutableStateFlow(null)
  val runningInfoStateFlow: StateFlow<RunningInfo?> = _runningInfoStateFlow.asStateFlow()
  val isArchivedStateFlow = taskToAgentStateFlow.flatMapLatest { taskToAgents ->
    val flows: List<Flow<Boolean>> = taskToAgents.map { taskToAgent ->
      taskToAgent.second.isArchivedStateFlow
    }
    combine(flows) { booleans ->
      booleans.all { it as Boolean }
    }
  }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = false
    )
  val isRunningStateFlow = taskToAgentStateFlow.flatMapLatest { taskToAgents ->
    val flows: List<Flow<Boolean>> = taskToAgents.map { taskToAgent ->
      taskToAgent.second.isRunningStateFlow
    }
    combine(flows) { booleans ->
      booleans.any { it as Boolean }
    }
  }
    .stateIn(
      scope = coroutineScope,
      started = SharingStarted.WhileSubscribed(),
      initialValue = false
    )

  suspend fun waitUntilFinished() {
    println("Arbiter.waitUntilFinished start")
    isRunningStateFlow.debounce(100).first { !it }
    println("Arbiter.waitUntilFinished end")
  }

  fun executeAsync(
    scenario: Scenario,
  ) {
    executeJob?.cancel()
    executeJob = coroutineScope.launch {
      execute(scenario)
    }
  }

  suspend fun execute(scenario: Scenario) {
    println("Arbiter.execute start")

    var finishedSuccessfully = false
    var retryRemain = scenario.retry
    do {
      _taskToAgentStateFlow.value.forEach {
        it.second.cancel()
      }
      _taskToAgentStateFlow.value = scenario.tasks.map { task ->
        task to Agent(task.agentConfig)
      }
      for ((index, taskAgent) in taskToAgentStateFlow.value.withIndex()) {
        val (task, agent) = taskAgent
        _runningInfoStateFlow.value = RunningInfo(
          allTasks = taskToAgentStateFlow.value.size,
          runningTasks = index + 1,
          retriedTasks = scenario.retry - retryRemain,
          maxRetry = scenario.retry,
        )
        agent.execute(task.goal)
        if (!agent.isArchivedStateFlow.value) {
          println("Arbiter.execute break because agent is not archived")
          break
        }
        if (index == taskToAgentStateFlow.value.size - 1) {
          println("Arbiter.execute all agents are archived")
          finishedSuccessfully = true
        }
        yield()
      }
    } while (!finishedSuccessfully && retryRemain-- > 0)
    _runningInfoStateFlow.value = null
    println("Arbiter.execute end")
  }

  fun cancel() {
    executeJob?.cancel()
    _taskToAgentStateFlow.value.forEach {
      it.second.cancel()
    }
  }

  class Builder {
    fun build(): Arbiter {
      return Arbiter()
    }
  }
}

fun arbiter(block: Arbiter.Builder.() -> Unit): Arbiter {
  val builder = Arbiter.Builder()
  builder.block()
  return builder.build()
}
