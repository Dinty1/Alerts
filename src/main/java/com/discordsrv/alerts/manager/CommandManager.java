package com.discordsrv.alerts.manager;

import com.discordsrv.alerts.Alerts;
import com.discordsrv.alerts.command.CommandImport;
import com.discordsrv.alerts.command.CommandReload;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandManager implements CommandExecutor, TabExecutor {

    private final Map<String, Method> commandClasses = new HashMap<>();
    private Alerts plugin;

    public CommandManager(Alerts plugin) {
        this.plugin = plugin;

        final List<Class<?>> commandClasses = Arrays.asList(
                CommandImport.class,
                CommandReload.class
        );

        for (Class<?> clazz : commandClasses) {
            for (Method method : clazz.getMethods()) {
                if (!method.isAnnotationPresent(com.discordsrv.alerts.command.Command.class)) {

                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return null;
    }
}
