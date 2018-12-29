/*
 * This file is part of JuniperBotJ.
 *
 * JuniperBotJ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * JuniperBotJ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with JuniperBotJ. If not, see <http://www.gnu.org/licenses/>.
 */
package ru.caramel.juniperbot.core.audit;

import net.dv8tion.jda.core.EmbedBuilder;
import org.apache.commons.lang3.StringUtils;
import ru.caramel.juniperbot.core.persistence.entity.AuditAction;
import ru.caramel.juniperbot.core.utils.CommonUtils;

public abstract class MessageAuditForwardProvider extends LoggingAuditForwardProvider {

    public static final String OLD_CONTENT = "old";

    public static final String MESSAGE_ID = "message_id";

    protected void addOldContentField(AuditAction action, EmbedBuilder embedBuilder) {
        embedBuilder.addField(messageService.getMessage("audit.message.oldContent.title"),
                getMessageValue(action, OLD_CONTENT), false);
    }

    protected void addAuthorField(AuditAction action, EmbedBuilder embedBuilder) {
        if (action.getUser() != null) {
            embedBuilder.addField(messageService.getMessage("audit.author.title"),
                    getReferenceContent(action.getUser(), false), true);
        }
    }

    protected String getMessageValue(AuditAction action, String key) {
        String oldContent = action.getAttribute(key, String.class);
        return StringUtils.isBlank(oldContent)
                ? messageService.getMessage("audit.message.noContent")
                : String.format("```%s```", CommonUtils.trimTo(oldContent, 994));
    }
}
