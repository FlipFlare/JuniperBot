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
package ru.juniperbot.api.subscriptions.integrations;

import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import club.minnced.discord.webhook.send.WebhookMessage;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import com.vk.api.sdk.callback.objects.messages.CallbackMessage;
import com.vk.api.sdk.objects.audio.AudioFull;
import com.vk.api.sdk.objects.base.Link;
import com.vk.api.sdk.objects.docs.Doc;
import com.vk.api.sdk.objects.docs.DocPreviewPhoto;
import com.vk.api.sdk.objects.pages.WikipageFull;
import com.vk.api.sdk.objects.photos.Photo;
import com.vk.api.sdk.objects.photos.PhotoAlbum;
import com.vk.api.sdk.objects.photos.PhotoSizes;
import com.vk.api.sdk.objects.polls.Poll;
import com.vk.api.sdk.objects.video.Video;
import com.vk.api.sdk.objects.wall.*;
import lombok.Getter;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;
import ru.juniperbot.api.model.VkInfo;
import ru.juniperbot.common.model.VkConnectionStatus;
import ru.juniperbot.common.persistence.entity.VkConnection;
import ru.juniperbot.common.persistence.repository.VkConnectionRepository;
import ru.juniperbot.common.utils.CommonUtils;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

import static ru.juniperbot.common.utils.CommonUtils.*;

@Service
public class VkSubscriptionServiceImpl extends BaseSubscriptionService<VkConnection, CallbackMessage<Wallpost>, VkInfo> implements VkSubscriptionService {

    private final static String CLUB_URL = "https://vk.com/club%s";

    private final static String WALL_URL = "https://vk.com/wall-%s_%s";

    private final static String PHOTO_URL = WALL_URL + "?z=photo-%s_%s";

    private final static String VIDEO_URL = WALL_URL + "?z=video-%s_%s";

    private final static String ALBUM_URL = "https://vk.com/album-%s_%s";

    private final static String AUDIO_URL = "https://vk.com/search?c[section]=audio&c[q]=%s&c[performer]=1";

    private final static Map<Integer, String> DOC_TYPE_NAMES;

    private final static int COLOR = CommonUtils.hex2Rgb("4A76A8").getRGB();

    private final static Map<WallpostAttachmentType, Integer> ATTACHMENT_PRIORITY;

    @Getter
    private List<WallpostAttachmentType> attachmentTypes;

    static {
        Map<Integer, String> types = new HashMap<>();
        types.put(1, "vk.message.documentType.text");
        types.put(2, "vk.message.documentType.archive");
        types.put(3, "vk.message.documentType.gif");
        types.put(4, "vk.message.documentType.picture");
        types.put(5, "vk.message.documentType.audio");
        types.put(6, "vk.message.documentType.video");
        types.put(7, "vk.message.documentType.money");
        types.put(8, "vk.message.documentType.unknown");
        DOC_TYPE_NAMES = Collections.unmodifiableMap(types);

        Map<WallpostAttachmentType, Integer> priorities = new LinkedHashMap<>();
        priorities.put(WallpostAttachmentType.PHOTO, 10);
        priorities.put(WallpostAttachmentType.POSTED_PHOTO, 9);
        priorities.put(WallpostAttachmentType.GRAFFITI, 8);
        priorities.put(WallpostAttachmentType.ALBUM, 7);
        priorities.put(WallpostAttachmentType.VIDEO, 6);
        priorities.put(WallpostAttachmentType.LINK, 5);
        priorities.put(WallpostAttachmentType.DOC, 4);
        priorities.put(WallpostAttachmentType.AUDIO, 3);
        priorities.put(WallpostAttachmentType.PAGE, 2);
        priorities.put(WallpostAttachmentType.POLL, 1);
        ATTACHMENT_PRIORITY = Collections.unmodifiableMap(priorities);
    }

    @Autowired
    private VkConnectionRepository repository;

    public VkSubscriptionServiceImpl(@Autowired VkConnectionRepository repository) {
        super(repository);
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        attachmentTypes = Collections.unmodifiableList(new ArrayList<>(ATTACHMENT_PRIORITY.keySet()));
    }

    @Override
    protected VkConnection createConnection(VkInfo info) {
        VkConnection connection = new VkConnection();
        connection.setStatus(VkConnectionStatus.CONFIRMATION);
        connection.setToken(UUID.randomUUID().toString());
        connection.setName(info.getName());
        connection.setConfirmCode(info.getCode());
        return connection;
    }

    @Override
    public VkInfo getUser(String userName) {
        throw new IllegalStateException();
    }

    @Override
    @Transactional(readOnly = true)
    public VkConnection getForToken(String token) {
        return repository.findByToken(token);
    }

    @Override
    @Transactional
    public String confirm(VkConnection connection, CallbackMessage message) {
        connection.setGroupId(message.getGroupId());
        connection.setStatus(VkConnectionStatus.CONNECTED);
        connection.getWebHook().setEnabled(true);
        return repository.save(connection).getConfirmCode();
    }

    @Override
    public void post(VkConnection connection, CallbackMessage<Wallpost> message) {
        if (!connection.getWebHook().isValid()) {
            return;
        }
        notifyConnection(message, connection);
    }

    @Override
    protected WebhookMessage createMessage(CallbackMessage<Wallpost> message, VkConnection connection) {
        Wallpost post = message.getObject();
        if (PostType.SUGGEST.equals(post.getPostType())) {
            return null; // do not post suggestions
        }

        if (connection.isGroupOnlyPosts() && !Objects.equals(connection.getGroupId(), post.getFromId() * -1)) {
            return null;
        }

        List<WallpostAttachment> attachments = new ArrayList<>(CollectionUtils.isNotEmpty(post.getAttachments()) ? post.getAttachments() : Collections.emptyList());
        attachments.sort((a, b) -> {
            Integer aP = ATTACHMENT_PRIORITY.get(a.getType());
            Integer bP = ATTACHMENT_PRIORITY.get(b.getType());
            return bP.compareTo(aP);
        });

        List<WebhookEmbedBuilder> processEmbeds = new ArrayList<>();

        Set<String> images = new HashSet<>();
        attachments.forEach(e -> processAttachment(images, connection, processEmbeds, message, e));

        List<WebhookEmbedBuilder> embeds = processEmbeds.size() > 10 ? processEmbeds.subList(0, 10) : processEmbeds;

        String content = trimTo(CommonUtils.parseVkLinks(HtmlUtils.htmlUnescape(post.getText())), 2000);
        WebhookEmbedBuilder contentEmbed = null;
        if (embeds.size() == 1) {
            contentEmbed = embeds.get(0);
        } else if (embeds.isEmpty() && StringUtils.isNotEmpty(content)) {
            contentEmbed = createBuilder();
            embeds.add(contentEmbed);
        }
        if (contentEmbed != null) {
            if (connection.isShowDate() && post.getDate() != null) {
                contentEmbed.setTimestamp(new Date(((long) post.getDate()) * 1000).toInstant());
            }
            String url = String.format(WALL_URL, message.getGroupId(), post.getId());
            setText(connection, contentEmbed, post.getText(), url);
            content = null; // show it on embed instead
        }

        if (connection.isMentionEveryone()) {
            content = CommonUtils.trimTo(content != null ? CommonUtils.EVERYONE + content : CommonUtils.EVERYONE,
                    2000);
        }
        WebhookMessageBuilder builder = new WebhookMessageBuilder().setContent(content);
        if (!embeds.isEmpty()) {
            builder.addEmbeds(embeds.stream().map(WebhookEmbedBuilder::build).collect(Collectors.toList()));
        }
        return !builder.isEmpty() ? builder.build() : null;
    }

    private WebhookEmbedBuilder initBuilder(CallbackMessage<Wallpost> message, List<WebhookEmbedBuilder> builders) {
        WebhookEmbedBuilder builder = createBuilder()
                .setFooter(new WebhookEmbed.EmbedFooter(String.format(CLUB_URL, message.getGroupId()), null));
        builders.add(builder);
        return builder;
    }

    private WebhookEmbedBuilder initBuilderIfRequired(CallbackMessage<Wallpost> message, List<WebhookEmbedBuilder> builders, int desiredLength) {
        WebhookEmbedBuilder prevBuilder = CollectionUtils.isNotEmpty(builders) ? builders.get(builders.size() - 1) : null;
        if (prevBuilder == null || getFields(prevBuilder).size() == 25) {
            return initBuilder(message, builders);
        }
        WebhookEmbed embed = prevBuilder.build();
        return getWebhookLength(embed) + desiredLength <= MessageEmbed.EMBED_MAX_LENGTH_BOT
                ? prevBuilder : initBuilder(message, builders);
    }

    private void addField(CallbackMessage<Wallpost> message, List<WebhookEmbedBuilder> builders, String name, String value, boolean inline) {
        WebhookEmbedBuilder prevBuilder = initBuilderIfRequired(message, builders, (name != null ? name.length() : 0) + (value != null ? value.length() : 0));
        prevBuilder.addField(new WebhookEmbed.EmbedField(inline, name, value));
    }

    private void addBlankField(CallbackMessage<Wallpost> message, List<WebhookEmbedBuilder> builders, boolean inline) {
        WebhookEmbedBuilder prevBuilder = CollectionUtils.isNotEmpty(builders) ? builders.get(builders.size() - 1) : null;
        if (prevBuilder == null || getFields(prevBuilder).size() == 24) { // do not add last empty fields
            prevBuilder = initBuilder(message, builders);
        }
        if (getFields(prevBuilder).size() > 0) {
            addField(message, builders, CommonUtils.ZERO_WIDTH_SPACE, CommonUtils.ZERO_WIDTH_SPACE, inline);
        }
    }

    private boolean hasImage(List<WebhookEmbedBuilder> builders) {
        WebhookEmbedBuilder prevBuilder = CollectionUtils.isNotEmpty(builders) ? builders.get(builders.size() - 1) : null;
        try {
            if (prevBuilder != null && FieldUtils.readField(prevBuilder, "imageUrl", true) != null) {
                return true;
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    private void processAttachment(Set<String> images, VkConnection connection, List<WebhookEmbedBuilder> builders, CallbackMessage<Wallpost> message, WallpostAttachment attachment) {
        if (connection.getAttachmentTypes() != null && connection.getAttachmentTypes().contains(attachment.getType().name())) {
            return; // ignore attachments
        }
        WebhookEmbedBuilder builder = CollectionUtils.isNotEmpty(builders) ? builders.get(builders.size() - 1) : null;

        boolean hasImage = hasImage(builders);
        switch (attachment.getType()) {
            case PHOTO:
                Photo photo = attachment.getPhoto();
                if (photo == null) {
                    return;
                }
                builder = initBuilder(message, builders);
                setPhoto(connection, images, builder, message, photo, true);
                break;
            case POSTED_PHOTO:
                PostedPhoto postedPhoto = attachment.getPostedPhoto();
                if (postedPhoto == null) {
                    return;
                }
                builder = initBuilder(message, builders);
                setPhoto(images, builder, postedPhoto.getPhoto604(), postedPhoto.getPhoto130());
                break;
            case VIDEO:
                Video video = attachment.getVideo();
                if (video == null) {
                    return;
                }
                String url = String.format(VIDEO_URL,
                        Math.abs(message.getGroupId()),
                        message.getObject().getId(),
                        Math.abs(video.getOwnerId()),
                        video.getId());

                builder = initBuilder(message, builders);
                setText(connection, builder, video.getTitle(), url);
                setPhoto(images, builder, video.getPhoto800(), video.getPhoto320(), video.getPhoto130());
                if (connection.isShowDate() && video.getDate() != null) {
                    builder.setTimestamp(new Date(((long) video.getDate()) * 1000).toInstant());
                }
                break;
            case AUDIO:
                AudioFull audio = attachment.getAudio();
                if (audio == null) {
                    return;
                }
                if (hasImage(builders)) {
                    initBuilder(message, builders);
                }
                if (StringUtils.isNotEmpty(audio.getArtist()) || StringUtils.isNotEmpty(audio.getTitle())) {
                    addBlankField(message, builders, false);
                }
                String artist = null;
                if (StringUtils.isNotEmpty(audio.getArtist())) {
                    artist = HtmlUtils.htmlUnescape(audio.getArtist());
                    String artistUrl = CommonUtils.makeLink(artist, String.format(AUDIO_URL, UriUtils.encode(artist, "UTF-8")));
                    addField(message, builders, getMessage(connection, "vk.message.audio.artist"), artistUrl, true);
                }
                if (StringUtils.isNotEmpty(audio.getTitle())) {
                    String title = HtmlUtils.htmlUnescape(audio.getTitle());
                    String fullTitle = (artist != null ? (artist + " - ") : "") + title;
                    String titleUrl = CommonUtils.makeLink(title, String.format(AUDIO_URL, UriUtils.encode(fullTitle, "UTF-8")));
                    addField(message, builders, getMessage(connection, "vk.message.audio.title"), titleUrl, true);
                }
                break;
            case DOC:
                Doc doc = attachment.getDoc();
                if (doc == null) {
                    return;
                }
                String type = DOC_TYPE_NAMES.get(doc.getType());
                if (type == null) {
                    return;
                }
                String name = mdLink(HtmlUtils.htmlUnescape(doc.getTitle()), doc.getUrl());

                String imgUrl = null;
                if ((doc.getType() == 3 || doc.getType() == 4)
                        && doc.getPreview() != null && doc.getPreview().getPhoto() != null
                        && CollectionUtils.isNotEmpty(doc.getPreview().getPhoto().getSizes())) {
                    DocPreviewPhoto preview = doc.getPreview().getPhoto();
                    Integer size = 0;
                    for (PhotoSizes sizes : preview.getSizes()) {
                        Integer total = sizes.getWidth() * sizes.getHeight();
                        if (total > size && sizes.getSrc() != null) {
                            size = total;
                            imgUrl = sizes.getSrc();
                        }
                    }
                }
                if (imgUrl != null) {
                    if (builder == null || hasImage) {
                        builder = initBuilder(message, builders);
                    }
                    setPhoto(images, builder, imgUrl);
                }
                addField(message, builders, getMessage(connection, "vk.message.documentType", getMessage(connection, type)), name, true);
                break;
            case GRAFFITI:
                Graffiti graffiti = attachment.getGraffiti();
                if (graffiti == null) {
                    return;
                }
                builder = initBuilder(message, builders);
                setPhoto(images, builder, graffiti.getPhoto586(), graffiti.getPhoto200());
                break;
            case LINK:
                Link link = attachment.getLink();
                if (link == null) {
                    return;
                }

                if (!hasImage && link.getPhoto() != null) {
                    builder = initBuilder(message, builders);
                    setPhoto(connection, images, builder, message, link.getPhoto(), false);
                }
                if (hasImage) {
                    initBuilder(message, builders);
                }

                boolean hasCaption = StringUtils.isNotEmpty(link.getCaption());
                if (hasCaption) {
                    addBlankField(message, builders, false);
                }
                addField(message, builders, getMessage(connection, "vk.message.link.title"),
                        trimTo(mdLink(link.getTitle(), link.getUrl()), MessageEmbed.TEXT_MAX_LENGTH), true);
                if (hasCaption) {
                    addField(message, builders, getMessage(connection, "vk.message.link.source"), HtmlUtils.htmlUnescape(link.getCaption()), true);
                }
                break;
            case POLL:
                Poll poll = attachment.getPoll();
                if (poll == null) {
                    return;
                }

                StringBuilder answers = new StringBuilder();
                for (int i = 0; i < poll.getAnswers().size(); i++) {
                    answers.append(i + 1).append(". ").append(HtmlUtils.htmlUnescape(poll.getAnswers().get(i).getText())).append('\n');
                }
                if (hasImage) {
                    initBuilder(message, builders);
                }
                addBlankField(message, builders, false);
                addField(message, builders, getMessage(connection, "vk.message.poll"),
                        trimTo(HtmlUtils.htmlUnescape(poll.getQuestion()), MessageEmbed.TEXT_MAX_LENGTH), true);
                addField(message, builders, getMessage(connection, "vk.message.poll.answers"),
                        trimTo(answers.toString(), MessageEmbed.TEXT_MAX_LENGTH), true);
                break;
            case PAGE:
                WikipageFull page = attachment.getPage();
                if (page == null) {
                    return;
                }
                if (hasImage) {
                    initBuilder(message, builders);
                }
                addBlankField(message, builders, false);
                addField(message, builders, getMessage(connection, "vk.message.page"),
                        trimTo(mdLink(HtmlUtils.htmlUnescape(page.getTitle()), page.getViewUrl()), MessageEmbed.TEXT_MAX_LENGTH), true);
                break;
            case ALBUM:
                PhotoAlbum album = attachment.getAlbum();
                if (album == null) {
                    return;
                }
                url = String.format(ALBUM_URL, message.getGroupId(), album.getId());

                builder = initBuilder(message, builders);
                if (album.getThumb() != null) {
                    setPhoto(connection, images, builder, message, album.getThumb(), false);
                }
                builder.setDescription(trimTo(HtmlUtils.htmlUnescape(album.getDescription()), MessageEmbed.TEXT_MAX_LENGTH));

                addBlankField(message, builders, false);
                addField(message, builders, getMessage(connection, "vk.message.album"),
                        trimTo(mdLink(HtmlUtils.htmlUnescape(album.getTitle()), url), MessageEmbed.TEXT_MAX_LENGTH), true);
                addField(message, builders, getMessage(connection, "vk.message.album.photos"),
                        String.valueOf(album.getSize()), true);
                break;
        }
    }

    private void setPhoto(Set<String> images, WebhookEmbedBuilder builder, String... urls) {
        String imageUrl = coalesce(urls);
        if (imageUrl != null && images.add(imageUrl)) {
            builder.setImageUrl(imageUrl);
        }
    }

    private void setPhoto(VkConnection connection, Set<String> images, WebhookEmbedBuilder builder, CallbackMessage<Wallpost> message, Photo photo, boolean showText) {
        String imageUrl = coalesce(photo.getPhoto2560(),
                photo.getPhoto1280(),
                photo.getPhoto807(),
                photo.getPhoto604(),
                photo.getPhoto130(),
                photo.getPhoto75());
        if (!UrlValidator.getInstance().isValid(imageUrl)) {
            return;
        }
        if (images.add(imageUrl)) {
            String url = String.format(PHOTO_URL,
                    Math.abs(message.getGroupId()),
                    message.getObject().getId(),
                    Math.abs(photo.getOwnerId()),
                    photo.getId());

            if (showText) {
                setText(connection, builder, photo.getText(), url);
            }

            builder.setImageUrl(imageUrl);
            if (connection.isShowDate() && photo.getDate() != null) {
                builder.setTimestamp(new Date(((long) photo.getDate()) * 1000).toInstant());
            }
        }
    }

    private void setText(VkConnection connection, WebhookEmbedBuilder builder, String text, String url) {
        if (StringUtils.isNotEmpty(text)) {
            if (connection.isShowPostLink()) {
                builder.setTitle(new WebhookEmbed.EmbedTitle(getMessage(connection, "vk.message.open"), url));
            }
            builder.setDescription(trimTo(CommonUtils.parseVkLinks(HtmlUtils.htmlUnescape(text)), MessageEmbed.TEXT_MAX_LENGTH));
        }
    }

    private static int getWebhookLength(WebhookEmbed embed) {
        int length = 0;
        if (embed.getTitle() != null)
            length += embed.getTitle().getText().length();
        if (embed.getDescription() != null)
            length += embed.getDescription().length();
        if (embed.getAuthor() != null)
            length += embed.getAuthor().getName().length();
        if (embed.getFooter() != null)
            length += embed.getFooter().getText().length();
        for (WebhookEmbed.EmbedField field : embed.getFields()) {
            length += field.getName().length() + field.getValue().length();
        }
        return length;
    }

    @SuppressWarnings("unchecked")
    private static List<WebhookEmbed.EmbedField> getFields(WebhookEmbedBuilder builder) {
        if (builder == null) {
            return Collections.emptyList();
        }
        try {
            List<WebhookEmbed.EmbedField> fields = (List<WebhookEmbed.EmbedField>) FieldUtils.readField(builder, "fields", true);
            return fields != null ? fields : Collections.emptyList();
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private WebhookEmbedBuilder createBuilder() {
        return new WebhookEmbedBuilder().setColor(COLOR);
    }
}
