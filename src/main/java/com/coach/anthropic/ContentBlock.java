package com.coach.anthropic;

/**
 * One block of a user/assistant turn's content. Replaces the old plain-{@code String}
 * content channel so a turn can carry text alongside file attachments.
 *
 * @see TextBlock
 * @see AttachmentBlock
 */
public sealed interface ContentBlock permits TextBlock, AttachmentBlock { }
