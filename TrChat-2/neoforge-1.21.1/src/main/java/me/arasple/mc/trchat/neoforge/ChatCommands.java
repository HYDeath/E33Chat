package me.arasple.mc.trchat.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

final class ChatCommands {
    @FunctionalInterface private interface Action { void execute(ChatService service) throws Exception; }
    private static int run(CommandContext<CommandSourceStack> context, Supplier<ChatService> supplier, Action action) throws CommandSyntaxException {
        ChatService service = supplier.get();
        if (service == null) { context.getSource().sendFailure(Component.literal("TrChat 尚未启动，请检查服务端日志。")); return 0; }
        try { action.execute(service); return 1; }
        catch (CommandSyntaxException ex) { throw ex; }
        catch (Exception ex) { context.getSource().sendFailure(Component.literal("操作失败，请检查配置和服务端日志。")); TrChatMod.LOGGER.error("TrChat command failed", ex); return 0; }
    }
    private static boolean admin(CommandSourceStack source) {
        return !source.isPlayer() || ChatPermissions.has(source.getPlayer(), ChatPermissions.ADMIN);
    }
    static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<ChatService> service) {
        var root = literal("trchat")
            .executes(c -> run(c, service, s -> s.info(c.getSource())))
            .then(literal("info").executes(c -> run(c, service, s -> s.info(c.getSource()))))
            .then(literal("list").executes(c -> run(c, service, s -> s.list(c.getSource()))))
            .then(literal("history").executes(c -> run(c, service, s -> s.showHistory(c.getSource().getPlayerOrException()))))
            .then(literal("global").then(argument("message", StringArgumentType.greedyString())
                .executes(c -> run(c, service, s -> s.globalChat(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "message"))))))
            .then(literal("reload").requires(ChatCommands::admin).executes(c -> run(c, service, s -> {
                s.reload(); c.getSource().sendSuccess(() -> Component.literal("TrChat 配置已重载。"), true);
            })))
            .then(literal("gui").requires(ChatCommands::admin).executes(c -> run(c, service, s -> s.clientGui(c.getSource().getPlayerOrException()))))
            .then(literal("mute").requires(ChatCommands::admin).then(argument("player", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(service.get() == null ? java.util.List.of() : service.get().names(), b))
                .then(argument("seconds", IntegerArgumentType.integer(1, 31536000))
                    .executes(c -> run(c, service, s -> s.mute(c.getSource(), StringArgumentType.getString(c, "player"), IntegerArgumentType.getInteger(c, "seconds")))))))
            .then(literal("unmute").requires(ChatCommands::admin).then(argument("player", StringArgumentType.word())
                .executes(c -> run(c, service, s -> s.mute(c.getSource(), StringArgumentType.getString(c, "player"), 0)))))
            .then(literal("globalmute").requires(ChatCommands::admin).then(argument("enabled", BoolArgumentType.bool())
                .executes(c -> run(c, service, s -> s.globalMute(c.getSource(), BoolArgumentType.getBool(c, "enabled"))))))
            .then(literal("broadcast").requires(ChatCommands::admin).then(argument("message", StringArgumentType.greedyString())
                .executes(c -> run(c, service, s -> s.announce(c.getSource(), StringArgumentType.getString(c, "message"))))))
            .then(literal("clear").requires(ChatCommands::admin).executes(c -> run(c, service, s -> s.clear(c.getSource()))));
        dispatcher.register(root);
        for (String alias : java.util.List.of("msg", "tell", "w", "m")) dispatcher.register(literal(alias)
            .then(argument("player", StringArgumentType.string())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(service.get() == null ? java.util.List.of() : service.get().privateNames(), b))
                .then(argument("message", StringArgumentType.greedyString())
                    .executes(c -> run(c, service, s -> s.privateChat(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "message")))))));
        for (String alias : java.util.List.of("reply", "r")) dispatcher.register(literal(alias)
            .then(argument("message", StringArgumentType.greedyString())
                .executes(c -> run(c, service, s -> s.reply(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "message"))))));
        dispatcher.register(literal("ignore")
            .executes(c -> run(c, service, s -> s.ignoreList(c.getSource().getPlayerOrException())))
            .then(argument("player", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(service.get() == null ? java.util.List.of() : service.get().names(), b))
                .executes(c -> run(c, service, s -> s.ignore(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "player"))))));
        dispatcher.register(literal("trgroup")
            .executes(c -> run(c, service, s -> c.getSource().sendSuccess(() -> Component.literal("群聊：" + String.join(", ", s.groups())), false)))
            .then(literal("join").then(argument("group", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(service.get() == null ? java.util.List.of() : service.get().groups(), b))
                .executes(c -> run(c, service, s -> s.groupJoin(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "group"))))))
            .then(literal("leave").executes(c -> run(c, service, s -> s.groupLeave(c.getSource().getPlayerOrException()))))
            .then(literal("msg").then(argument("group", StringArgumentType.word()).then(argument("message", StringArgumentType.greedyString())
                .executes(c -> run(c, service, s -> s.groupChat(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "group"), StringArgumentType.getString(c, "message"))))))));
        dispatcher.register(literal("e33chat").then(literal("gui").requires(ChatCommands::admin)
            .executes(c -> run(c, service, s -> s.clientGui(c.getSource().getPlayerOrException())))));
    }
}
