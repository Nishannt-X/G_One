package com.example.g_one.agent.tools.buyers;

import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code connect_buyer(buyerId)}: dials a suggested buyer. Refused in code unless the farmer's latest turn was an
 * explicit yes ({@link BuyerConsent}), whatever the model believes. With no {@link BuyerConnector} wired, the connection
 * is only announced on the console, and the browser call simulator joins as the buyer.
 */
@Component
public class ConnectBuyerTool implements Tool {
    public static final String NAME = "connect_buyer";
    private static final Logger log = LoggerFactory.getLogger(ConnectBuyerTool.class);
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {"buyerId": {"type": "string", "description": "The buyerId from find_buyers"}},
             "required": ["buyerId"]}""");

    private final BuyerDirectory directory;
    private final BuyerConsent consent;
    private final Supplier<BuyerConnector> connector;

    @Autowired
    public ConnectBuyerTool(BuyerDirectory directory, BuyerConsent consent, ObjectProvider<BuyerConnector> connector) {
        this(directory, consent, connector::getIfUnique);
    }

    /** @param connector gives the connector, or null when none is wired */
    public ConnectBuyerTool(BuyerDirectory directory, BuyerConsent consent, Supplier<BuyerConnector> connector) {
        this.directory = directory;
        this.consent = consent;
        this.connector = connector;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Phone a buyer from find_buyers so the farmer can talk to them through you. Only after the farmer has "
                + "said a clear yes to your question about calling that buyer.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<String> id = ToolArgs.text(args, "buyerId");
        if (id.isEmpty()) return ToolResult.error("buyerId is required.");
        Optional<Buyer> buyer = directory.find(id.get()).filter(b -> consent.wasSuggested(ctx.callId(), b.id()));
        if (buyer.isEmpty()) return ToolResult.error("That buyer was not suggested on this call. Use find_buyers first.");
        if (!consent.allowed(ctx.callId(), buyer.get().id())) {
            return ToolResult.error("Not connected: the farmer has not said a clear yes. Ask them: shall I call "
                    + buyer.get().name() + " now? Please say yes or no.");
        }
        BuyerConnector.ConnectRequest request = consent.requested(ctx.callId(), buyer.get());
        BuyerConnector c = connector.get();
        if (c != null) {
            try {
                c.connect(request);
            } catch (RuntimeException e) {
                log.warn("Connecting a buyer failed on call {} ({})", ctx.callId(), e.getClass().getSimpleName());
                return ToolResult.error("The buyer could not be called right now. Apologise and offer to try later.");
            }
        }
        return ToolResult.ok("Calling " + buyer.get().name() + " now. Tell the farmer you are calling them and to "
                + "stay on the line, in one short sentence.");
    }
}
