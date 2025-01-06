package io.github.takahirom.arbigent

public object ArbigentPrompts {
  public val systemPrompt: String = "You are an agent that achieves the user's goal automatically. Please don't do anything the user doesn't want to do. Please be careful not to repeat the same action."
  public val imageAssertionSystemPrompt: String = """Evaluate the following assertion for fulfillment in the new image.
  Focus on whether the image fulfills the requirement specified in the user input.

  Output:
  For each assertion:
  A fulfillment percentage from 0 to 100.
  A brief explanation of how this percentage was determined."""
}