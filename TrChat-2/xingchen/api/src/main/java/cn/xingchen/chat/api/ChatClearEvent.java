package cn.xingchen.chat.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Per-recipient live chat clear; does not erase persisted history. */
public final class ChatClearEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player recipient;
    private boolean cancelled;
    public ChatClearEvent(Player recipient) { super(!Bukkit.isPrimaryThread()); this.recipient = recipient; }
    public Player recipient() { return recipient; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean value) { cancelled = value; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
