package com.github.takahirom.arbiter.sample.test

import com.github.takahirom.arbiter.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ArbiterScenarioContentExecutorTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun tests() = runTest {
    ArbiterCorotuinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val agentConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      ai(FakeAi())
    }
    val arbiterScenarioExecutor = ArbiterScenarioExecutor {
    }
    val arbiterScenario = ArbiterScenario(
      id = "id2",
      agentTasks = listOf(
        ArbiterAgentTask("id1", "goal1", agentConfig),
        ArbiterAgentTask("id2", "goal2", agentConfig)
      ),
      maxStepCount = 10,
    )
    arbiterScenarioExecutor.execute(
      arbiterScenario
    )
    advanceUntilIdle()
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun lastInterceptorCalledFirst() = runTest {
    ArbiterCorotuinesDispatcher.dispatcher = coroutineContext[CoroutineDispatcher]!!
    val order = mutableListOf<Int>()
    val agentConfig = AgentConfig {
      deviceFactory { FakeDevice() }
      ai(FakeAi())
      addInterceptor(
        object : ArbiterInitializerInterceptor {
          override fun intercept(
            device: ArbiterDevice,
            chain: ArbiterInitializerInterceptor.Chain
          ) {
            order.add(200)
            chain.proceed(device)
            order.add(300)
          }
        }
      )
      addInterceptor(
        object : ArbiterInitializerInterceptor {
          override fun intercept(
            device: ArbiterDevice,
            chain: ArbiterInitializerInterceptor.Chain
          ) {
            order.add(100)
            chain.proceed(device)
            order.add(400)
          }
        }
      )
    }
    val arbiterScenarioExecutor = ArbiterScenarioExecutor {
    }
    val arbiterScenario = ArbiterScenario(
      id = "id2",
      listOf(
        ArbiterAgentTask("id1", "goal1", agentConfig),
        ArbiterAgentTask("id2", "goal2", agentConfig)
      ),
      maxStepCount = 10,
    )
    arbiterScenarioExecutor.execute(
      arbiterScenario
    )
    advanceUntilIdle()
    assertEquals(listOf(100, 200, 300, 400, 100, 200, 300, 400), order)
  }
}
