package cn.xingchen.chat.api;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.UUID;

/** Fired on the recipient's entity scheduler after all XC-Chat policy checks.
 * Cancel only after successfully replacing that recipient's vanilla delivery. */
public final class ChatDeliveryEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player recipient;
    private final UUID id, sender;
    private final String account, channel, target;
    private final Component display, body, rendered;
    private boolean cancelled;
    public ChatDeliveryEvent(Player recipient, UUID id, UUID sender, String account, String channel,
                             String target, Component display, Component body, Component rendered) {
        super(!Bukkit.isPrimaryThread());
        this.recipient = recipient; this.id = id; this.sender = sender; this.account = account;
        this.channel = channel; this.target = target; this.display = display; this.body = body; this.rendered = rendered;
    }
    public Player recipient() { return recipient; }
    public UUID id() { return id; }
    public UUID sender() { return sender; }
    public String account() { return account; }
    public String channel() { return channel; }
    public String target() { return target; }
    public Component display() { return display; }
    public Component body() { return body; }
    public Component rendered() { return rendered; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean value) { cancelled = value; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
