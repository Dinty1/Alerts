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

package com.discordsrv.alerts.util;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import com.discordsrv.alerts.Alerts;
import org.bukkit.Bukkit;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class DiscordUtil {

    /**
     * Return the given String with Markdown escaped. Useful for sending things to Discord.
     *
     * @param text String to escape markdown in
     * @return String with markdown escaped
     */
    public static String escapeMarkdown(String text) {
        return text == null ? "" : text.replace("_", "\\_").replace("*", "\\*").replace("~", "\\~").replace("|", "\\|").replace(">", "\\>").replace("`", "\\`");
    }

    /**
     * Send a webhook message to Discord
     *
     * @param message    Message to send
     * @param webhookUrl URL of the webhook to send to
     */
    public static void sendWebhookMessage(MessageFormat message, String webhookUrl) {
        // TODO fix fields
        Bukkit.getScheduler().runTaskAsynchronously(Alerts.getPlugin(), () -> {
            WebhookClient webhookClient = WebhookClient.withUrl(webhookUrl);
            WebhookMessageBuilder messageBuilder = new WebhookMessageBuilder();

            messageBuilder.setUsername(message.getWebhookName());
            messageBuilder.setAvatarUrl(message.getWebhookAvatarUrl());
            messageBuilder.setContent(message.getContent());

            final List<WebhookEmbed.EmbedField> fields = new ArrayList<>();

            if (message.hasEmbed()) {
                if (message.getFields() != null) {
                    for (MessageFormat.Field messageField : message.getFields()) {
                        fields.add(new WebhookEmbed.EmbedField(messageField.isInline(), messageField.getTitle(), messageField.getValue()));
                    }
                }

                WebhookEmbed embed = new WebhookEmbed(
                        message.getTimestamp() != null ? message.getTimestamp().atOffset(ZoneOffset.UTC) : null,
                        message.getColor() != null ? message.getColor().getRGB() : null,
                        message.getDescription(),
                        message.getThumbnailUrl(),
                        message.getImageUrl(),
                        message.getFooterText() != null ? new WebhookEmbed.EmbedFooter(message.getFooterText(), message.getFooterIconUrl()) : null,
                        message.getTitle() != null ? new WebhookEmbed.EmbedTitle(message.getTitle(), message.getTitleUrl()) : null,
                        message.getAuthorName() != null || message.getAuthorUrl() != null ? new WebhookEmbed.EmbedAuthor(message.getAuthorName(), message.getAuthorImageUrl(), message.getAuthorUrl()) : null,
                        fields
                );
                messageBuilder.addEmbeds(embed);
            }

            webhookClient.send(messageBuilder.build());
        });

    }
}
