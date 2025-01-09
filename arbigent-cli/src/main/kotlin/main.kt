package io.github.takahirom.arbigent.cli

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.defaultByName
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import com.github.ajalt.clikt.parameters.types.choice
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaicBlocking
import com.jakewharton.mosaic.ui.Color.*
import com.jakewharton.mosaic.ui.Color.Companion.Black
import com.jakewharton.mosaic.ui.Color.Companion.Green
import com.jakewharton.mosaic.ui.Color.Companion.Red
import com.jakewharton.mosaic.ui.Color.Companion.White
import com.jakewharton.mosaic.ui.Color.Companion.Yellow
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.takahirom.arbigent.ArbigentAi
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.ArbigentLogLevel
import io.github.takahirom.arbigent.ArbigentProject
import io.github.takahirom.arbigent.ArbigentScenario
import io.github.takahirom.arbigent.ArbigentScenarioExecutor
import io.github.takahirom.arbigent.ArbigentScenarioExecutorState
import io.github.takahirom.arbigent.ArbigentDeviceOs
import io.github.takahirom.arbigent.OpenAIAi
import io.github.takahirom.arbigent.arbigentLogLevel
import io.github.takahirom.arbigent.fetchAvailableDevicesByOs
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.util.toLowerCasePreservingASCIIRules
import kotlinx.coroutines.delay
import java.io.File
import kotlin.system.exitProcess

sealed class AiConfig(name: String) : OptionGroup(name)

class OpenAIAiConfig : AiConfig("Options for OpenAI API AI") {
  private val defaultEndpoint = "https://api.openai.com/v1/"
  val openAiEndpoint by option(help = "Endpoint URL (default: $defaultEndpoint)")
    .default(defaultEndpoint, defaultForHelp = defaultEndpoint)
  val openAiModelName by option(help = "Model name (default: gpt-4o-mini)")
    .default("gpt-4o-mini", "gpt-4o-mini")
}

class GeminiAiConfig : AiConfig("Options for Gemini API AI") {
  private val defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai/"
  val geminiEndpoint by option(help = "Endpoint URL (default: $defaultEndpoint)")
    .default(defaultEndpoint, defaultForHelp = defaultEndpoint)
  val geminiModelName by option(help = "Model name (default: gemini-1.5-flash)")
    .default("gemini-1.5-flash", "gemini-1.5-flash")
}

class AzureOpenAiConfig : AiConfig("Options for Azure OpenAI") {
  val azureOpenAIEndpoint by option(help = "Endpoint URL")
    .prompt("Endpoint URL")
  val azureOpenAIApiVersion by option(help = "API version")
    .default("2024-10-21")
  val azureOpenAIModelName by option(help = "Model name (default: gpt-4o-mini)")
    .default("gpt-4o-mini")
}

class ArbigentCli : CliktCommand() {
  private val aiType by option(help = "Type of AI to use")
    .groupChoice(
      "openai" to OpenAIAiConfig(),
      "gemini" to GeminiAiConfig(),
      "azureopenai" to AzureOpenAiConfig()
    )
    .defaultByName("openai")

  private val os by option(help = "Target operating system")
    .choice("android", "ios", "web")
    .default("android")

  private val scenarioFile by option(help = "Path to the scenario YAML file")
    .prompt("Scenario file path")

  @OptIn(ArbigentInternalApi::class)
  override fun run() {
    val ai: ArbigentAi = aiType.let { aiType ->
      when (aiType) {
        is OpenAIAiConfig -> OpenAIAi(
          apiKey = System.getenv("OPENAI_API_KEY")
            ?: throw IllegalArgumentException("Environment variable OPENAI_API_KEY is not set"),
          baseUrl = aiType.openAiEndpoint,
          modelName = aiType.openAiModelName,
        )

        is GeminiAiConfig -> OpenAIAi(
          apiKey = System.getenv("GEMINI_API_KEY")
            ?: throw IllegalArgumentException("Environment variable GEMINI_API_KEY is not set"),
          baseUrl = aiType.geminiEndpoint,
          modelName = aiType.geminiModelName,
        )

        is AzureOpenAiConfig -> OpenAIAi(
          apiKey = System.getenv("AZURE_OPENAI_API_KEY")
            ?: throw IllegalArgumentException("Environment variable AZURE_OPENAI_API_KEY is not set"),
          baseUrl = aiType.azureOpenAIEndpoint,
          modelName = aiType.azureOpenAIModelName,
          requestBuilderModifier = {
            parameter("api-version", aiType.azureOpenAIApiVersion)
            header("api-key", System.getenv("AZURE_OPENAI_API_KEY").orEmpty())
          }
        )
      }
    }

    val os =
      ArbigentDeviceOs.entries.find { it.name.toLowerCasePreservingASCIIRules() == os.toLowerCasePreservingASCIIRules() }
        ?: throw IllegalArgumentException("Invalid OS. The OS should be one of ${
          ArbigentDeviceOs.values().joinToString(", ") { it.name.toLowerCasePreservingASCIIRules() }
        }")
    val device = fetchAvailableDevicesByOs(os).first().connectToDevice()
    arbigentLogLevel = ArbigentLogLevel.ERROR

    val arbigentProject = ArbigentProject(
      file = File(scenarioFile),
      aiFactory = { ai },
      deviceFactory = { device }
    )
    runMosaicBlocking {
      LaunchedEffect(Unit) {
        arbigentProject.execute()
        // Show the result
        delay(100)
        if (!arbigentProject.isSuccess()) {
          exitProcess(0)
        } else {
          exitProcess(1)
        }
      }
      Column {
        val assignments by arbigentProject.scenarioAssignmentsFlow.collectAsState(arbigentProject.scenarioAssignments())
        assignments.forEach { (scenario, scenarioExecutor) ->
          ScenarioRow(scenario, scenarioExecutor)
        }
      }
    }
  }

}

@Composable
fun ScenarioRow(scenario: ArbigentScenario, scenarioExecutor: ArbigentScenarioExecutor) {
  val runningInfo by scenarioExecutor.runningInfoFlow.collectAsState(scenarioExecutor.runningInfo())
  val scenarioState by scenarioExecutor.scenarioStateFlow.collectAsState(scenarioExecutor.scenarioState())
  Row {
    val bg = when (scenarioState) {
      ArbigentScenarioExecutorState.Running -> Yellow
      ArbigentScenarioExecutorState.Success -> Green
      ArbigentScenarioExecutorState.Failed -> Red
      ArbigentScenarioExecutorState.Idle -> White
    }
    Text(
      scenarioState.name(),
      modifier = Modifier
        .background(bg)
        .padding(horizontal = 1),
      color = Black,
    )
    if (runningInfo != null) {
      Text(
        runningInfo.toString().lines()
          .joinToString(" "),
        modifier = Modifier.padding(horizontal = 1).background(Companion.Magenta),
        color = White,
      )
    }
    Text(
      "Goal:" + scenario.agentTasks.lastOrNull()?.goal?.take(80) + "...",
      modifier = Modifier.padding(horizontal = 1),
    )
  }
}

fun main(args: Array<String>) = ArbigentCli().main(args)
