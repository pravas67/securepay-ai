package com.securepay.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxToolCalls        tool calls allowed while answering one message; the loop stops beyond this
 * @param memoryMessages      how many recent messages (user + assistant) are remembered per user
 * @param requestsPerMinute   chat messages each user may send per minute (cost and abuse control)
 */
@ConfigurationProperties("securepay.agent")
public record AgentProperties(int maxToolCalls, int memoryMessages, int requestsPerMinute) {
}
