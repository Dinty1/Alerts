package com.discordsrv.alerts.command;


import org.bukkit.command.CommandSender;

public class CommandImport {

    @Command(commandNames = { "import", "importlegacy" },
            helpMessage = "Imports configuration from DiscordSRV's alerts.yml",
            permission = "alerts.import")
    public void execute(CommandSender sender, String[] args) {

    }
}
