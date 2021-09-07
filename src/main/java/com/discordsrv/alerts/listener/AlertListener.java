/*
 * Alerts: A bukkit plugin to send customizable alerts to Discord driven by events and commands
 * Copyright (C) 2021 Alerts contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.discordsrv.alerts.listener;

import alexh.weak.Dynamic;
import alexh.weak.Weak;
import com.discordsrv.alerts.Alerts;
import com.discordsrv.alerts.collection.ExpiringDualHashBidiMap;
import com.discordsrv.alerts.hook.DiscordSRVHook;
import com.discordsrv.alerts.util.*;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Guild;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Message;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.objects.Lag;
import github.scarsz.discordsrv.util.DiscordUtil;
import github.scarsz.discordsrv.util.WebhookUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.RegisteredListener;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelEvaluationException;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AlertListener implements Listener {

    private static final Pattern VALID_CLASS_NAME_PATTERN = Pattern.compile("([\\p{L}_$][\\p{L}\\p{N}_$]*\\.)*[\\p{L}_$][\\p{L}\\p{N}_$]*");
    private static final List<String> BLACKLISTED_CLASS_NAMES = Arrays.asList(
            // Causes issues with logins with some plugins
            "com.destroystokyo.paper.event.player.PlayerHandshakeEvent",
            // Causes the server to synchronize with the main thread & breaks team color on Paper
            "org.bukkit.event.player.PlayerChatEvent"
    );
    private static final List<String> SYNC_EVENT_NAMES = Arrays.asList(
            // Needs to be sync because block data will be stale by time async task runs
            "BlockBreakEvent"
    );

    private static final List<Class<?>> BLACKLISTED_CLASSES = new ArrayList<>();

    private final Map<String, String> validClassNameCache = new ExpiringDualHashBidiMap<>(TimeUnit.MINUTES.toMillis(1));
    private final Set<String> activeTriggers = new HashSet<>();

    static {
        for (String className : BLACKLISTED_CLASS_NAMES) {
            try {
                BLACKLISTED_CLASSES.add(Class.forName(className));
            } catch (ClassNotFoundException ignored) {}
        }
    }

    private final Alerts plugin;
    private final RegisteredListener listener;
    private final List<Dynamic> alerts = new ArrayList<>();
    private boolean registered = false;

    public AlertListener(Alerts plugin) {
        this.plugin = plugin;
        listener = new RegisteredListener(
                this,
                (listener, event) -> runAlertsForEvent(event),
                EventPriority.MONITOR,
                plugin,
                false
        );
    }

    public void register() {
        //
        // Bukkit's API has no easy way to listen for all events
        // The best thing you can do is add a listener to all the HandlerList's
        // (and ignore some problematic events)
        //
        // Thus, we have to resort to making a proxy HandlerList.allLists list that adds our listener whenever a new
        // handler list is created by an event being initialized
        //
        try {
            Field allListsField = HandlerList.class.getDeclaredField("allLists");
            allListsField.setAccessible(true);

            if (Modifier.isFinal(allListsField.getModifiers())) {
                Field modifiersField = Field.class.getDeclaredField("modifiers");
                modifiersField.setAccessible(true);
                modifiersField.setInt(allListsField, allListsField.getModifiers() & ~Modifier.FINAL);
            }

            // set the HandlerList.allLists field to be a proxy list that adds our listener to all initializing lists
            allListsField.set(null, new ArrayList<HandlerList>() {
                {
                    // add any already existing handler lists to our new proxy list
                    synchronized (this) {
                        this.addAll(HandlerList.getHandlerLists());
                    }
                }

                @Override
                public boolean addAll(Collection<? extends HandlerList> c) {
                    boolean changed = false;
                    for (HandlerList handlerList : c) {
                        if (add(handlerList)) changed = true;
                    }
                    return changed;
                }

                @Override
                public boolean add(HandlerList list) {
                    boolean added = super.add(list);
                    addListener(list);
                    return added;
                }
            });
        } catch (NoSuchFieldException | IllegalAccessException e) {
            plugin.error(e);
        }
        registered = true;
    }

    private void addListener(HandlerList handlerList) {
        for (Class<?> blacklistedClass : BLACKLISTED_CLASSES) {
            try {
                HandlerList list = (HandlerList) blacklistedClass.getMethod("getHandlerList").invoke(null);
                if (handlerList == list) {
                    plugin.debug("Skipping registering HandlerList for " + blacklistedClass.getName() + " for alerts");
                    return;
                }
            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                plugin.debug("Failed to check if HandlerList was for " + blacklistedClass.getName() + ": " + e);
            }
        }
        for (StackTraceElement stackTraceElement : Thread.currentThread().getStackTrace()) {
            String match = BLACKLISTED_CLASS_NAMES.stream().filter(className -> stackTraceElement.getClassName().equals(className)).findAny().orElse(null);
            if (match != null && stackTraceElement.getMethodName().equals("<clinit>")) {
                plugin.debug("Skipping registering HandlerList for " + match + " for alerts (during event init)");
                return;
            }
        }
        if (Arrays.stream(handlerList.getRegisteredListeners()).noneMatch(listener::equals)) handlerList.register(listener);
    }

    public void reloadAlerts() {
        validClassNameCache.clear();
        activeTriggers.clear();
        alerts.clear();
        Optional<List<Map<?, ?>>> optionalAlerts = plugin.config().getOptional("Alerts");
        boolean any = optionalAlerts.isPresent() && !optionalAlerts.get().isEmpty();
        if (registered) unregister();
        if (any) {
            register();
            long count = optionalAlerts.get().size();
            plugin.info(optionalAlerts.get().size() + " alert" + (count > 1 ? "s" : "") + " registered");

            for (Map<?, ?> map : optionalAlerts.get()) {
                Dynamic alert = Dynamic.from(map);
                alerts.add(alert);
                activeTriggers.addAll(getTriggers(alert));
            }
        }
    }

    public List<Dynamic> getAlerts() {
        return alerts;
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        registered = false;
    }

    public void runAlertsForEvent(Object event) {
        boolean command = event instanceof PlayerCommandPreprocessEvent || event instanceof ServerCommandEvent;

        boolean active = false;
        String eventName = getEventName(event);
        for (String trigger : activeTriggers) {
            if (command && trigger.startsWith("/")) {
                active = true;
                break;
            } else if (trigger.equals(eventName.toLowerCase())) {
                active = true;
                break;
            }
        }
        if (!active) {
            // remove us from HandlerLists that we don't need (we can do this here, since we have the full class name)
            if (event instanceof Event) ((Event) event).getHandlers().unregister(this);
            return;
        }

        for (int i = 0; i < alerts.size(); i++) {
            Dynamic alert = alerts.get(i);
            Set<String> triggers = getTriggers(alert);
            boolean async = true;

            Dynamic asyncDynamic = alert.get("Async");
            if (asyncDynamic.isPresent()) {
                if (asyncDynamic.convert().intoString().equalsIgnoreCase("false")
                        || asyncDynamic.convert().intoString().equalsIgnoreCase("no")) {
                    async = false;
                }
            }

            outer:
            for (String syncName : SYNC_EVENT_NAMES) {
                for (String trigger : triggers) {
                    if (trigger.equalsIgnoreCase(syncName)) {
                        async = false;
                        break outer;
                    }
                }
            }

            if (async) {
                int alertIndex = i;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> process(event, alert, triggers, alertIndex));
            } else {
                process(event, alert, triggers, i);
            }
        }
    }

    private Set<String> getTriggers(Dynamic alert) {
        Set<String> triggers = new HashSet<>();
        Dynamic triggerDynamic = alert.get("Trigger");
        if (triggerDynamic.isList()) {
            triggers.addAll(triggerDynamic.children()
                    .map(Weak::asString)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet())
            );
        } else if (triggerDynamic.isString()) {
            triggers.add(triggerDynamic.asString().toLowerCase());
        }

        Set<String> finalTriggers = new HashSet<>();
        for (String trigger : triggers) {
            if (!trigger.startsWith("/")) {
                String className = validClassNameCache.get(trigger);
                if (className == null) {
                    // event trigger, make sure it's a valid class name
                    Matcher matcher = VALID_CLASS_NAME_PATTERN.matcher(trigger);
                    if (matcher.find()) {
                        // valid class name found
                        className = matcher.group();
                    }
                    validClassNameCache.put(trigger, className);
                }
                finalTriggers.add(className);
                continue;
            }
            finalTriggers.add(trigger);
        }
        return finalTriggers;
    }

    private String getEventName(Object event) {
        return event instanceof Event ? ((Event) event).getEventName() : event.getClass().getSimpleName();
    }

    private void process(Object event, Dynamic alert, Set<String> triggers, int alertIndex) {
        // TODO remove later pls
        MessageFormat tempMessageFormat = MessageFormatUtil.getMessageFromConfiguration(plugin.config(), "Alerts." + alertIndex);
        com.discordsrv.alerts.util.DiscordUtil.sendWebhookMessage(tempMessageFormat, "https://discord.com/api/webhooks/860931721427419156/TCKqXs3y693G4MGjUOYVcLlkK4vvTDMQ-I8TEijgkJvhcCfr3CWpT29vFtp01p9QImLz");

        Player player = event instanceof PlayerEvent ? ((PlayerEvent) event).getPlayer() : null;
        if (player == null) {
            // some things that do deal with players are not properly marked as a player event
            // this will check to see if a #getPlayer() method exists on events coming through
            try {
                Method getPlayerMethod = event.getClass().getMethod("getPlayer");
                if (getPlayerMethod.getReturnType().equals(Player.class)) {
                    player = (Player) getPlayerMethod.invoke(event);
                }
            } catch (Exception ignored) {
                // we tried ¯\_(ツ)_/¯
            }
        }

        CommandSender sender = null;
        String command = null;
        List<String> args = new LinkedList<>();

        if (event instanceof PlayerCommandPreprocessEvent) {
            sender = player;
            command = ((PlayerCommandPreprocessEvent) event).getMessage().substring(1);
        } else if (event instanceof ServerCommandEvent) {
            sender = ((ServerCommandEvent) event).getSender();
            command = ((ServerCommandEvent) event).getCommand();
        }
        if (StringUtils.isNotBlank(command)) {
            String[] split = command.split(" ", 2);
            String commandBase = split[0];
            if (split.length == 2) args.addAll(Arrays.asList(split[1].split(" ")));

            // transform "discordsrv:discord" to just "discord" for example
            if (commandBase.contains(":")) commandBase = commandBase.substring(commandBase.lastIndexOf(":") + 1);

            command = commandBase + (split.length == 2 ? (" " + split[1]) : "");
        }

        MessageFormat messageFormat = MessageFormatUtil.getMessageFromConfiguration(plugin.config(), "Alerts." + alertIndex);
        if (messageFormat == null) {
            plugin.debug("Not sending an alert because the MessageFormat is null");
            return;
        }

        for (String trigger : triggers) {
            String eventName = getEventName(event);
            if (trigger.startsWith("/")) {
                if (StringUtils.isBlank(command) || !command.toLowerCase().split("\\s+|$", 2)[0].equals(trigger.substring(1))) continue;
            } else {
                // make sure the called event matches what this alert is supposed to trigger on
                if (!eventName.equalsIgnoreCase(trigger)) continue;
            }

            // make sure alert should run even if event is cancelled
            if (event instanceof Cancellable && ((Cancellable) event).isCancelled()) {
                Dynamic ignoreCancelledDynamic = alert.get("IgnoreCancelled");
                boolean ignoreCancelled = ignoreCancelledDynamic.isPresent() ? ignoreCancelledDynamic.as(Boolean.class) : true;
                if (ignoreCancelled) {
                    plugin.debug("Not running alert for event " + eventName + ": event was cancelled");
                    return;
                }
            }

            // TODO maybe rename to "target" & allow webhook urls
            Dynamic targetsDynamic = alert.get("Target");
            if (targetsDynamic == null) {
                plugin.debug("Not running alert for trigger " + trigger + ": no target was defined");
                return;
            }
            Set<String> channels = new HashSet<>();
            if (targetsDynamic.isList()) {
                targetsDynamic.children()
                        .map(Weak::asString)
                        .filter(Objects::nonNull)
                        .forEach(channels::add);
            } else if (targetsDynamic.isString()) {
                channels.add(targetsDynamic.asString());
            }
            Function<Function<String, Collection<TextChannel>>, Set<TextChannel>> channelResolver = converter -> {
                Set<TextChannel> textChannels = new HashSet<>();
                channels.forEach(channel -> textChannels.addAll(converter.apply(channel)));
                textChannels.removeIf(Objects::isNull);
                return textChannels;
            };

            Set<TextChannel> textChannels = channelResolver.apply(s -> {
                TextChannel target = DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(s);
                return Collections.singleton(target);
            });
            if (textChannels.isEmpty()) {
                textChannels.addAll(channelResolver.apply(s ->
                        DiscordUtil.getJda().getTextChannelsByName(s, false)
                ));
            }
            if (textChannels.isEmpty()) {
                textChannels.addAll(channelResolver.apply(s -> NumberUtils.isDigits(s) ?
                        Collections.singleton(DiscordUtil.getJda().getTextChannelById(s)) : null));
            }

            if (textChannels.size() == 0) {
                plugin.debug("Not running alert for trigger " + trigger + ": no target channel was defined/found (channels: " + channels + ")");
                return;
            }

            for (TextChannel textChannel : textChannels) {
                // check alert conditions
                boolean allConditionsMet = true;
                Dynamic conditionsDynamic = alert.dget("Conditions");
                if (conditionsDynamic.isPresent()) {
                    Iterator<Dynamic> conditions = conditionsDynamic.children().iterator();
                    while (conditions.hasNext()) {
                        Dynamic dynamic = conditions.next();
                        String expression = dynamic.convert().intoString();
                        try {
                            Boolean value = new SpELExpressionBuilder(expression)
                                    .withPluginVariables()
                                    .withVariable("event", event)
                                    .withVariable("server", Bukkit.getServer())
                                    .withVariable("discordsrv", plugin.getDiscordSRVHook().map(DiscordSRVHook::getDiscordSRV).orElse(null))
                                    .withVariable("alerts", plugin)
                                    .withVariable("player", player)
                                    .withVariable("sender", sender)
                                    .withVariable("command", command)
                                    .withVariable("args", args)
                                    .withVariable("allArgs", String.join(" ", args))
                                    .withVariable("channel", textChannel)
                                    .withVariable("jda", plugin.getDiscordSRVHook().map(DiscordSRVHook::getJDA).orElse(null))
                                    .evaluate(event, Boolean.class);
                            plugin.debug("Condition \"" + expression + "\" -> " + value);
                            if (value != null && !value) {
                                allConditionsMet = false;
                                break;
                            }
                        } catch (ParseException e) {
                            plugin.error("Error while parsing expression \"" + expression + "\" for trigger \"" + trigger + "\" -> " + e.getMessage());
                        } catch (SpelEvaluationException e) {
                            plugin.error("Error while evaluating expression \"" + expression + "\" for trigger \"" + trigger + "\" -> " + e.getMessage());
                        }
                    }
                    if (!allConditionsMet) continue;
                }

                CommandSender finalSender = sender;
                String finalCommand = command;

                Player finalPlayer = player;
                BiFunction<String, Boolean, String> translator = (content, needsEscape) -> {
                    if (content == null) return null;

                    // evaluate any SpEL expressions
                    Map<String, Object> variables = new HashMap<>();
                    variables.put("event", event);
                    variables.put("server", Bukkit.getServer());
                    variables.put("discordsrv", plugin.getDiscordSRVHook().map(DiscordSRVHook::getDiscordSRV).orElse(null));
                    variables.put("alerts", plugin);
                    variables.put("player", finalPlayer);
                    variables.put("sender", finalSender);
                    variables.put("command", finalCommand);
                    variables.put("args", args);
                    variables.put("allArgs", String.join(" ", args));
                    variables.put("channel", textChannel);
                    variables.put("jda", plugin.getDiscordSRVHook().map(DiscordSRVHook::getJDA).orElse(null));
                    content = NamedValueFormatter.formatExpressions(content, event, variables);

                    // replace any normal placeholders
                    content = NamedValueFormatter.format(content, key -> {
                        switch (key) {
                            case "tps":
                                return Lag.getTPSString();
                            case "time":
                            case "date":
                                return plugin.getTimeProvider().timeStamp();
                            case "ping":
                                return finalPlayer != null ? plugin.getPlayerProvider().getPing(finalPlayer) : "-1";
                            case "name":
                            case "username":
                                return finalPlayer != null ? finalPlayer.getName() : "";
                            case "displayname":
                                return finalPlayer != null ? MessageUtil.strip(needsEscape ? DiscordUtil.escapeMarkdown(finalPlayer.getDisplayName()) : finalPlayer.getDisplayName()) : "";
                            case "world":
                                return finalPlayer != null ? finalPlayer.getWorld().getName() : "";
                            case "embedavatarurl":
                                return finalPlayer != null ? plugin.getAvatarProvider().getAvatarUrl(finalPlayer) : DiscordUtil.getJda().getSelfUser().getEffectiveAvatarUrl();
                            case "botavatarurl":
                                return plugin.getDiscordSRVHook().map(hook -> hook.getJDA().getSelfUser().getEffectiveAvatarUrl()).orElse("https://cdn.discordapp.com/embed/avatars/0.png");
                            case "botname":
                                return plugin.getDiscordSRVHook().map(hook -> {
                                    Guild guild = hook.getDiscordSRV().getMainGuild();
                                    return guild != null ? guild.getSelfMember().getEffectiveName() : hook.getJDA().getSelfUser().getName();
                                }).orElse("Bot");
                            default:
                                return "{" + key + "}";
                        }
                    });

                    DiscordSRVHook hook = plugin.getDiscordSRVHook().orElse(null);
                    if (hook != null) content = hook.translateEmotes(content, textChannel.getGuild());
                    content = PlaceholderUtil.replacePlaceholdersToDiscord(content, finalPlayer);
                    return content;
                };

                if (messageFormat.isUseWebhooks()) {
                    if (plugin.isDiscordSRVHookEnabled()) {
                        Message message = DiscordSRV.translateMessage(messageFormat.toDiscordSRV(), translator);
                        if (message == null) {
                            plugin.debug("Not sending alert because it is configured to have no message content");
                            return;
                        }

                        WebhookUtil.deliverMessage(textChannel,
                                translator.apply(messageFormat.getWebhookName(), false),
                                translator.apply(messageFormat.getWebhookAvatarUrl(), false),
                                message.getContentRaw(), message.getEmbeds().stream().findFirst().orElse(null));
                    } else { // Webhooks wanted but no dsrv hook
                        // TODO actual webhook url thing
                        com.discordsrv.alerts.util.DiscordUtil.sendWebhookMessage(messageFormat, "https://discord.com/api/webhooks/860931721427419156/TCKqXs3y693G4MGjUOYVcLlkK4vvTDMQ-I8TEijgkJvhcCfr3CWpT29vFtp01p9QImLz");
                    }
                } else {
                    //DiscordUtil.queueMessage(textChannel, message);
                    // TODO more stuff
                }
            }
        }
    }

}
