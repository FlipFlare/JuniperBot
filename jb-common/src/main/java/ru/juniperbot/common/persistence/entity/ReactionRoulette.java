/*
 * This file is part of JuniperBot.
 *
 * JuniperBot is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * JuniperBot is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with JuniperBot. If not, see <http://www.gnu.org/licenses/>.
 */
package ru.juniperbot.common.persistence.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Type;
import ru.juniperbot.common.persistence.entity.base.GuildEntity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.validation.constraints.Min;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "reaction_roulette")
@ToString
@Getter
@Setter
@NoArgsConstructor
public class ReactionRoulette extends GuildEntity {

    private static final long serialVersionUID = -302896048638134104L;

    @Column(name = "is_enabled")
    private boolean enabled;

    @Column(name = "is_reaction")
    private boolean reaction;

    @Column
    private int percent = 1;

    @Column
    @Min(0)
    private int threshold = 0;

    @Type(type = "jsonb")
    @Column(name = "ignored_channels", columnDefinition = "json")
    private List<Long> ignoredChannels;

    @Type(type = "jsonb")
    @Column(name = "selected_emotes", columnDefinition = "json")
    private Set<String> selectedEmotes;

    public ReactionRoulette(long guildId) {
        this.guildId = guildId;
    }
}
