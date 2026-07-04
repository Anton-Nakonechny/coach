package com.coach.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaFileDocumentSource;
import com.anthropic.models.beta.messages.BetaFileImageSource;
import com.anthropic.models.beta.messages.BetaImageBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaRequestDocumentBlock;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.MessageCreateParams;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anthropic SDK gateway — sends one Messages request per conversation turn.
 *
 * <p>Uses the <em>beta</em> Messages endpoint so a turn's content can mix text with
 * file attachments referenced by Files API {@code file_id} (image vs document block
 * chosen by {@link AttachmentBlock.Kind}). The {@code files-api-2025-04-14} beta header
 * is required to reference uploaded files.
 *
 * <p>{@code thinking} / {@code output_config.effort} are merged into the request as raw
 * JSON via {@code putAdditionalBodyProperty}, mirroring how the Python client uses
 * {@code extra_body} to stay decoupled from SDK typing.
 */
@Component
public class SdkAnthropicGateway {

    private static final String FILES_BETA = "files-api-2025-04-14";

    private final AnthropicClient client;

    public SdkAnthropicGateway(AnthropicClient client) {
        this.client = client;
    }

    public List<AnthropicBlock> createMessage(String modelId, int maxTokens,
                                              List<ApiMessage> messages,
                                              Map<String, Object> extraBody) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(modelId)
                .maxTokens(maxTokens)
                .addBeta(FILES_BETA);

        for (ApiMessage turn : messages) {
            BetaMessageParam.Role role = "assistant".equals(turn.role())
                    ? BetaMessageParam.Role.ASSISTANT
                    : BetaMessageParam.Role.USER;
            builder.addMessage(BetaMessageParam.builder()
                    .role(role)
                    .contentOfBetaContentBlockParams(toBlockParams(turn.content()))
                    .build());
        }

        // "ignored if not applicable" already resolved upstream — merge whatever
        // extra body fields apply (thinking / output_config) as raw JSON.
        extraBody.forEach((key, value) -> builder.putAdditionalBodyProperty(key, JsonValue.from(value)));

        BetaMessage response = client.beta().messages().create(builder.build());

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> new AnthropicBlock(AnthropicBlock.TYPE_TEXT, text.text()))
                .toList();
    }

    private static List<BetaContentBlockParam> toBlockParams(List<ContentBlock> blocks) {
        List<BetaContentBlockParam> out = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock t) {
                out.add(BetaContentBlockParam.ofText(
                        BetaTextBlockParam.builder().text(t.text()).build()));
            } else if (block instanceof AttachmentBlock a) {
                if (a.kind() == AttachmentBlock.Kind.IMAGE) {
                    out.add(BetaContentBlockParam.ofImage(BetaImageBlockParam.builder()
                            .source(BetaFileImageSource.builder().fileId(a.fileId()).build())
                            .build()));
                } else {
                    out.add(BetaContentBlockParam.ofDocument(BetaRequestDocumentBlock.builder()
                            .source(BetaFileDocumentSource.builder().fileId(a.fileId()).build())
                            .build()));
                }
            }
        }
        return out;
    }
}
