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
package ru.caramel.juniperbot.module.ranking.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Sets;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.PropertyPlaceholderHelper;
import ru.caramel.juniperbot.core.persistence.entity.GuildConfig;
import ru.caramel.juniperbot.core.persistence.entity.LocalMember;
import ru.caramel.juniperbot.core.persistence.entity.LocalUser;
import ru.caramel.juniperbot.core.persistence.repository.LocalMemberRepository;
import ru.caramel.juniperbot.core.persistence.repository.LocalUserRepository;
import ru.caramel.juniperbot.core.service.*;
import ru.caramel.juniperbot.core.utils.MapPlaceholderResolver;
import ru.caramel.juniperbot.module.ranking.model.RankingInfo;
import ru.caramel.juniperbot.module.ranking.model.Reward;
import ru.caramel.juniperbot.module.ranking.persistence.entity.Cookie;
import ru.caramel.juniperbot.module.ranking.persistence.entity.Ranking;
import ru.caramel.juniperbot.module.ranking.persistence.entity.RankingConfig;
import ru.caramel.juniperbot.module.ranking.persistence.repository.CookieRepository;
import ru.caramel.juniperbot.module.ranking.persistence.repository.RankingConfigRepository;
import ru.caramel.juniperbot.module.ranking.persistence.repository.RankingRepository;
import ru.caramel.juniperbot.module.ranking.utils.RankingUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class RankingServiceImpl implements RankingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RankingServiceImpl.class);

    private static PropertyPlaceholderHelper placeholderHelper = new PropertyPlaceholderHelper("{", "}");

    @Autowired
    private LocalMemberRepository memberRepository;

    @Autowired
    private LocalUserRepository userRepository;

    @Autowired
    private RankingRepository rankingRepository;

    @Autowired
    private RankingConfigRepository rankingConfigRepository;

    @Autowired
    private CookieRepository cookieRepository;

    @Autowired
    private MessageService messageService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private ImportProvider importProvider;

    @Autowired
    private MemberService memberService;

    @Autowired
    private DiscordService discordService;

    @Autowired
    private ContextService contextService;

    private static Object DUMMY = new Object();

    private Cache<String, Object> coolDowns = CacheBuilder.newBuilder()
            .concurrencyLevel(4)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();

    private final Set<String> calculateQueue = Sets.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    @Transactional
    @Override
    public RankingConfig getConfig(Guild guild) {
        return getConfig(guild.getIdLong());
    }

    @Transactional
    @Override
    public RankingConfig save(RankingConfig config) {
        return rankingConfigRepository.save(config);
    }

    @Transactional(readOnly = true)
    @Override
    public boolean isEnabled(long serverId) {
        return rankingConfigRepository.isEnabled(serverId);
    }

    @Transactional
    @Override
    public RankingConfig getConfig(long serverId) {
        RankingConfig config = rankingConfigRepository.findByGuildId(serverId);
        if (config == null) {
            GuildConfig guildConfig = configService.getOrCreate(serverId);
            config = new RankingConfig();
            config.setGuildConfig(guildConfig);
            rankingConfigRepository.save(config);
        }
        return config;
    }

    @Transactional
    @Override
    public RankingInfo getRankingInfo(Member member) {
        Ranking ranking = getRanking(member);
        if (ranking != null) {
            RankingInfo info = RankingUtils.calculateInfo(ranking);
            info.setCookies(cookieRepository.countByRecipient(ranking.getMember()));
            return info;
        }
        return null;
    }

    @Override
    public long countRankings(String serverId) {
        return rankingRepository.countByGuildId(serverId);
    }

    @Transactional
    @Override
    public Page<RankingInfo> getRankingInfos(long guildId, String search, Pageable pageable) {
        Page<Ranking> rankings = rankingRepository.findByGuildId(String.valueOf(guildId), search != null ? search.toLowerCase() : "", pageable);
        Map<Long, Long> cookiesMap = new HashMap<>();
        if (rankings.hasContent()) {
            Map<Long, LocalMember> memberMap = rankings.getContent().stream()
                    .collect(Collectors.toMap(k -> k.getMember().getId(), Ranking::getMember));
            List<Object[]> cookies = cookieRepository.countByRecipients(memberMap.values());
            cookies.forEach(e -> cookiesMap.put((Long)e[0], (Long)e[1]));
        }
        return rankings.map(e -> {
            RankingInfo info = RankingUtils.calculateInfo(e);
            if (cookiesMap.containsKey(e.getMember().getId())) {
                info.setCookies(cookiesMap.get(e.getMember().getId()));
            }
            return info;
        });
    }

    @Transactional
    @Override
    public void onMessage(GuildMessageReceivedEvent event) {
        String memberKey = String.format("%s_%s", event.getGuild().getId(), event.getAuthor().getId());

        RankingConfig config = getConfig(event.getGuild());
        if (!memberService.isApplicable(event.getMember()) || !config.isEnabled() || isBanned(config, event.getMember())) {
            return;
        }

        Ranking ranking = getRanking(event.getMember());

        if (coolDowns.getIfPresent(memberKey) == null) {
            int level = RankingUtils.getLevelFromExp(ranking.getExp());
            ranking.setExp(ranking.getExp() + RandomUtils.nextLong(15, 25));
            rankingRepository.save(ranking);
            calculateQueue.add(event.getGuild().getId());
            coolDowns.put(memberKey, DUMMY);

            int newLevel = RankingUtils.getLevelFromExp(ranking.getExp());
            if (newLevel < 1000 && level != newLevel) {
                if (config.isAnnouncementEnabled()) {
                    if (config.isWhisper()) {
                        try {
                            contextService.queue(event.getGuild(), event.getAuthor().openPrivateChannel(), c -> {
                                String content = getAnnounce(config, event.getAuthor().getAsMention(), newLevel);
                                sendAnnounce(config, c, content);
                            });
                        } catch (Exception e) {
                            LOGGER.warn("Could not open private channel for {}", event.getAuthor(), e);
                        }
                    } else {
                        MessageChannel channel = config.getAnnouncementChannelId() != null ?
                                event.getGuild().getTextChannelById(config.getAnnouncementChannelId()) : null;
                        String content = getAnnounce(config, event.getMember().getAsMention(), newLevel);
                        sendAnnounce(config, channel != null ? channel : event.getChannel(), content);
                    }
                }
                updateRewards(config, event.getMember(), ranking);
            }
        }

        if (CollectionUtils.isNotEmpty(event.getMessage().getMentionedUsers())
                && StringUtils.isNotEmpty(event.getMessage().getContentRaw())
                && event.getMessage().getContentRaw().contains("\uD83C\uDF6A")) {
            Date checkDate = DateTime.now().minusMinutes(10).toDate();
            for (User user : event.getMessage().getMentionedUsers()) {
                if (!user.isBot() && !Objects.equals(user, event.getAuthor())) {
                    Member recipientMember = event.getGuild().getMember(user);
                    if (recipientMember != null) {
                        LocalMember recipient = memberService.getOrCreate(recipientMember);
                        giveCookie(ranking.getMember(), recipient, checkDate);
                    }
                }
            }
        }
    }

    private void sendAnnounce(RankingConfig config, MessageChannel channel, String content) {
        if (config.isEmbed()) {
            messageService.onEmbedMessage(channel, content);
        } else {
            messageService.sendMessageSilent(channel::sendMessage, content);
        }
    }

    private void giveCookie(LocalMember sender, LocalMember recipient, Date checkDate) {
        if (!cookieRepository.isFull(sender, recipient, checkDate)) {
            cookieRepository.save(new Cookie(sender, recipient));
        }
    }

    @Transactional
    @Override
    public void setLevel(long serverId, long userId, int level) {
        if (level > 1000) {
            level = RankingUtils.MAX_LEVEL;
        } else if (level < 0) {
            level = 0;
        }
        LocalMember localMember = memberRepository.findByGuildIdAndUserId(String.valueOf(serverId),
                String.valueOf(userId));

        Ranking ranking = getRanking(localMember);

        if (ranking != null) {
            ranking.setExp(RankingUtils.getLevelTotalExp(level));
            rankingRepository.save(ranking);
            rankingRepository.recalculateRank(String.valueOf(serverId));
            RankingConfig config = getConfig(serverId);
            if (discordService.isConnected(serverId)) {
                Guild guild = discordService.getShardManager().getGuildById(serverId);
                if (guild != null) {
                    Member member = guild.getMemberById(userId);
                    if (member != null) {
                        updateRewards(config, member, ranking);
                    }
                }
            }
        }
    }

    @Transactional
    @Override
    public void sync(Guild guild) {
        List<LocalMember> members = memberService.syncMembers(guild);
        RankingConfig rankingConfig = getConfig(guild);
        members.forEach(e -> {
            Member member = guild.getMemberById(e.getUser().getUserId());
            if (member != null) {
                if (memberService.isApplicable(member)) {
                    memberService.updateIfRequired(member, e);
                    updateRewards(rankingConfig, member, getRanking(e));
                }
            } else if (rankingConfig.isResetOnLeave()) {
                rankingRepository.resetMember(e);
            }
        });
        memberRepository.save(members);
        rankingRepository.recalculateRank(guild.getId());
    }

    @Transactional
    @Override
    public long importRanking(Guild guild) {
        List<LocalMember> members = memberService.syncMembers(guild);
        Map<String, LocalMember> membersMap = members.stream()
                .collect(Collectors.toMap(u -> u.getUser().getUserId(), e -> e));

        AtomicLong count = new AtomicLong();

        importProvider.export(guild.getIdLong(), e -> {
            for (RankingInfo info : e) {
                try {
                    LocalMember member = membersMap.get(info.getId());
                    if (member == null) {
                        member = new LocalMember();
                        member.setGuildId(guild.getId());
                        member.setEffectiveName(info.getNick());

                        LocalUser user = userRepository.findByUserId(info.getId());
                        if (user == null) {
                            user = new LocalUser();
                            user.setUserId(info.getId());
                        }
                        if (StringUtils.isNotEmpty(info.getAvatarUrl())) {
                            user.setAvatarUrl(info.getAvatarUrl());
                        }
                        user.setName(info.getName());
                        user.setDiscriminator(info.getDiscriminator());
                        userRepository.save(user);
                        member.setUser(user);
                        memberRepository.save(member);
                    }
                    Ranking ranking = getRanking(member);
                    ranking.setExp(info.getTotalExp());
                    rankingRepository.save(ranking);
                    count.incrementAndGet();
                } catch (Exception ex) {
                    LOGGER.error("Import batch failed", ex);
                }
            }
        });

        sync(guild);
        return count.longValue();
    }

    @Transactional
    @Override
    public void resetAll(long serverId) {
        String server = String.valueOf(serverId);
        rankingRepository.resetAll(server);
        rankingRepository.recalculateRank(server);
    }

    private void updateRewards(RankingConfig config, Member member, Ranking ranking) {
        Member self = member.getGuild().getSelfMember();
        if (!discordService.isConnected(member.getGuild().getIdLong())
                || CollectionUtils.isEmpty(config.getRewards())
                || !self.hasPermission(Permission.MANAGE_ROLES)) {
            return;
        }

        int newLevel = RankingUtils.getLevelFromExp(ranking.getExp());

        List<Reward> rewards = config.getRewards().stream()
                .filter(e -> e.getRoleId() != null && e.getLevel() <= newLevel)
                .sorted(Comparator.comparing(Reward::getLevel))
                .collect(Collectors.toList());

        if (rewards.isEmpty()) {
            return;
        }
        Reward highest = rewards.remove(rewards.size() - 1);

        List<Reward> rewardsToAdd = new ArrayList<>();
        List<Reward> rewardsToRemove = new ArrayList<>();
        rewards.forEach(e -> (e.isReset() ? rewardsToRemove : rewardsToAdd).add(e));
        rewardsToAdd.add(highest);

        Set<Role> rolesToAdd = getRoles(member, rewardsToAdd);
        Set<Role> rolesToRemove = getRoles(member, rewardsToRemove);
        member.getGuild().getController().modifyMemberRoles(member, rolesToAdd, rolesToRemove).queue();
    }

    private Set<Role> getRoles(Member member, List<Reward> rewards) {
        Member self = member.getGuild().getSelfMember();
        return rewards.stream()
                .map(Reward::getRoleId)                                          // map by id
                .map(roleId -> member.getGuild().getRoleById(roleId))            // find actual role object
                .filter(role -> role != null && self.canInteract(role) && !role.isManaged())          // check that we can assign that role
                .collect(Collectors.toSet());
    }

    @Override
    public boolean isBanned(RankingConfig config, Member member) {
        if (config.getBannedRoles() == null) {
            return false;
        }
        List<String> bannedRoles = Arrays.asList(config.getBannedRoles());
        return CollectionUtils.isNotEmpty(member.getRoles()) && member.getRoles().stream()
                .anyMatch(e -> bannedRoles.contains(e.getName().toLowerCase()) || bannedRoles.contains(e.getId()));
    }

    private String getAnnounce(RankingConfig config, String mention, int level) {
        MapPlaceholderResolver resolver = new MapPlaceholderResolver();
        resolver.put("user", mention);
        resolver.put("level", String.valueOf(level));
        String announce = config.getAnnouncement();
        if (StringUtils.isBlank(announce)) {
            announce = messageService.getMessage("discord.command.rank.levelup");
        }
        return placeholderHelper.replacePlaceholders(announce, resolver);
    }

    @Transactional
    public Ranking getRanking(LocalMember member) {
        Ranking ranking = rankingRepository.findByMember(member);
        if (ranking == null && member != null) {
            ranking = new Ranking();
            ranking.setMember(member);
            ranking.setRank(rankingRepository.countByGuildId(member.getGuildId()) + 1);
            rankingRepository.save(ranking);
        }
        return ranking;
    }

    @Transactional
    public Ranking getRanking(Member member) {
        Ranking ranking = rankingRepository.findByGuildIdAndUserId(member.getGuild().getId(), member.getUser().getId());
        if (ranking == null) {
            LocalMember localMember = memberService.getOrCreate(member);
            ranking = new Ranking();
            ranking.setMember(localMember);
            ranking.setRank(rankingRepository.countByGuildId(member.getGuild().getId()) + 1);
            rankingRepository.save(ranking);
        }
        return ranking;
    }

    @Transactional
    public Ranking getRanking(String guildId, String userId) {
        Ranking ranking = rankingRepository.findByGuildIdAndUserId(guildId, userId);
        if (ranking == null) {
            LocalMember localMember = memberRepository.findByGuildIdAndUserId(guildId, userId);
            if (localMember != null) {
                ranking = new Ranking();
                ranking.setMember(localMember);
                ranking.setRank(rankingRepository.countByGuildId(guildId) + 1);
                rankingRepository.save(ranking);
            }
        }
        return ranking;
    }

    @Scheduled(fixedDelay = 300000)
    @Override
    public void calculateQueue() {
        Set<String> queue;
        synchronized (calculateQueue) {
            queue = new HashSet<>(calculateQueue);
            calculateQueue.clear();
        }
        queue.forEach(rankingRepository::recalculateRank);
    }
}
