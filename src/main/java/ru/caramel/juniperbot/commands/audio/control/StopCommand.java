package ru.caramel.juniperbot.commands.audio.control;

import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import ru.caramel.juniperbot.commands.audio.AudioCommand;
import ru.caramel.juniperbot.commands.model.BotContext;
import ru.caramel.juniperbot.commands.model.CommandGroup;
import ru.caramel.juniperbot.commands.model.CommandSource;
import ru.caramel.juniperbot.commands.model.DiscordCommand;
import ru.caramel.juniperbot.integration.discord.model.DiscordException;

@DiscordCommand(
        key = "стоп",
        description = "Остановить воспроизведение с очисткой плейлиста",
        source = CommandSource.GUILD,
        group = CommandGroup.MUSIC,
        priority = 107)
public class StopCommand extends AudioCommand {

    @Override
    public boolean doInternal(MessageReceivedEvent message, BotContext context, String content) throws DiscordException {
        messageManager.onMessage(message.getChannel(), playerService.getInstance(message.getGuild()).stop()
                ? "Воспроизведение остановлено :stop_button: " :"Воспроизведение не запущено");
        return true;
    }
}