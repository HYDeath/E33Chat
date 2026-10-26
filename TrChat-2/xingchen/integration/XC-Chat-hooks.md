# XC-Chat bridge hooks (private Xingchen workspace)

The public companion is intentionally only an adapter. In the private Xingchen checkout:

1. Copy `api/src/main/java/cn/xingchen/chat/api/{ChatDeliveryEvent,ChatClearEvent,ChatChannelProvider}.java` to the matching package under `plugins/XC-Chat/src/main/java/`.
2. Make `ChatPlugin` implement `ChatChannelProvider`; `activeChannel(UUID)` returns the existing channel map value or `world`, and `whisperTarget(UUID)` returns the existing last whisper target or an empty string.
3. For `/chat clear`, dispatch `new ChatClearEvent(player)` on that player's existing `Folias.entity` callback before the 100 blank lines. Skip the vanilla clear only when the event was cancelled.
4. In the existing validated `send(...)` loop, after mute/repeat/cooldown/recipient resolution and inside each `Folias.entity` callback, dispatch `ChatDeliveryEvent(recipient, messageId, senderUUID, senderName, channelId, privateTarget, displayComponent, bodyComponent, renderedComponent)`. Only suppress the original `recipient.sendMessage(message)` if the event was cancelled.
5. Let the companion listen at LOWEST priority for `AsyncChatEvent`. If XC-Core has a pending prompt for this connected E33 player, remove exactly one leading channel marker (`«w»`, `«g»`, `«t»`, `«s»`, or `«m:<player>»`) before XC-Core consumes the response.

Keep all channel policy and recipient lookup in the original methods. Dispatching the delivery event earlier, before the recipient collection is resolved, or from a non-owner region thread is incorrect. The JAR must contain these API classes (the companion declares them compile-only, so it does not shade a competing copy).
