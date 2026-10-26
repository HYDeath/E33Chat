package cn.xingchen.chat.api;
import java.util.UUID;
/** Public integration surface; no guild/party implementation is distributed. */
public interface ChatChannelProvider {
    String activeChannel(UUID player);
    String whisperTarget(UUID player);
}
