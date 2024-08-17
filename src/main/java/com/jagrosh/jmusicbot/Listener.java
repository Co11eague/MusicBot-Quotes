/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot;

import com.jagrosh.jmusicbot.utils.OtherUtil;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.ShutdownEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageDeleteEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.jagrosh.jmusicbot.JMusicBot.LOG;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class Listener extends ListenerAdapter
{
    private final Bot bot;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);


    public Listener(Bot bot)
    {
        this.bot = bot;
    }
    
    @Override
    public void onReady(ReadyEvent event) 
    {
        if(event.getJDA().getGuildCache().isEmpty())
        {
            Logger log = LoggerFactory.getLogger("MusicBot");
            log.warn("This bot is not on any guilds! Use the following link to add the bot to your guilds!");
            log.warn(event.getJDA().getInviteUrl(JMusicBot.RECOMMENDED_PERMS));
        }
        credit(event.getJDA());
        event.getJDA().getGuilds().forEach((guild) -> 
        {
            try
            {
                LocalTime scheduledTime = LocalTime.of(12, 0);

                scheduler.scheduleAtFixedRate(() -> sendScheduledMessage(event.getJDA(), guild.retrieveEmotes().complete()), calculateInitialDelay(scheduledTime), 24 * 60 * 60, TimeUnit.SECONDS);

                String defpl = bot.getSettingsManager().getSettings(guild).getDefaultPlaylist();
                VoiceChannel vc = bot.getSettingsManager().getSettings(guild).getVoiceChannel(guild);
                if(defpl!=null && vc!=null && bot.getPlayerManager().setUpHandler(guild).playFromDefault())
                {
                    guild.getAudioManager().openAudioConnection(vc);
                }
            }
            catch(Exception ignore) {}
        });
        if(bot.getConfig().useUpdateAlerts())
        {
            bot.getThreadpool().scheduleWithFixedDelay(() -> 
            {
                try
                {
                    User owner = bot.getJDA().retrieveUserById(bot.getConfig().getOwnerId()).complete();
                    String currentVersion = OtherUtil.getCurrentVersion();
                    String latestVersion = OtherUtil.getLatestVersion();
                    if(latestVersion!=null && !currentVersion.equalsIgnoreCase(latestVersion))
                    {
                        String msg = String.format(OtherUtil.NEW_VERSION_AVAILABLE, currentVersion, latestVersion);
                        owner.openPrivateChannel().queue(pc -> pc.sendMessage(msg).queue());
                    }
                }
                catch(Exception ignored) {} // ignored
            }, 0, 24, TimeUnit.HOURS);
        }
    }
    
    @Override
    public void onGuildMessageDelete(GuildMessageDeleteEvent event) 
    {
        bot.getNowplayingHandler().onMessageDelete(event.getGuild(), event.getMessageIdLong());
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event)
    {
        bot.getAloneInVoiceHandler().onVoiceUpdate(event);
    }

    @Override
    public void onShutdown(ShutdownEvent event) 
    {
        bot.shutdown();
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) 
    {
        credit(event.getJDA());
    }
    
    // make sure people aren't adding clones to dbots
    private void credit(JDA jda)
    {
        Guild dbots = jda.getGuildById(110373943822540800L);
        if(dbots==null)
            return;
        if(bot.getConfig().getDBots())
            return;
        jda.getTextChannelById(119222314964353025L)
                .sendMessage("This account is running JMusicBot. Please do not list bot clones on this server, <@"+bot.getConfig().getOwnerId()+">.").complete();
        dbots.leave().queue();
    }

    private long calculateInitialDelay(LocalTime scheduledTime) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime scheduledDateTime = LocalDateTime.of(now.toLocalDate(), scheduledTime);

        if (now.toLocalTime().isAfter(scheduledTime)) {
            scheduledDateTime = scheduledDateTime.plusDays(1);
        }
        LOG.info(String.valueOf(TimeUnit.NANOSECONDS.toSeconds(java.time.Duration.between(now, scheduledDateTime).toNanos())));
        return TimeUnit.NANOSECONDS.toSeconds(java.time.Duration.between(now, scheduledDateTime).toNanos());
    }
    private void sendScheduledMessage(JDA jda, List<ListedEmote> serverEmotes) {
        TextChannel channel = jda.getTextChannelById(202397765941198848L);
        if (channel != null) {
            MessageEmbed randomQuote = getRandomQuoteFromCSV();
            channel.sendMessage(randomQuote).queue(message -> {

                if (!serverEmotes.isEmpty()) {
                    Emote randomEmote = getRandomEmote(serverEmotes);
                    message.addReaction(randomEmote).queue();
                }
            });
        }
    }

    private Emote getRandomEmote(List<ListedEmote> emotes) {
        Random random = new Random();
        int randomIndex = random.nextInt(emotes.size());
        return emotes.get(randomIndex);
    }

    private MessageEmbed getRandomQuoteFromCSV() {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("quotes/quotes.csv");
             InputStreamReader reader = new InputStreamReader(inputStream);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader())) {

            List<CSVRecord> records = csvParser.getRecords();
            if (!records.isEmpty()) {
                Random random = new Random();
                int randomIndex = random.nextInt(records.size());
                CSVRecord randomRecord = records.get(randomIndex);

                String quote = randomRecord.get("quote");
                String author = randomRecord.get("author");

                // Create a rich embed with quote and author
                EmbedBuilder embedBuilder = new EmbedBuilder()
                        .setTitle("Dienos mintis")
                        .setDescription(quote)
                        .setFooter("- " + author);

                return embedBuilder.build();
            }

        } catch (IOException e) {
            return createDebugEmbed("Error reading CSV file: " + e.getMessage());
        }

        return createDebugEmbed("No records found in CSV file.");
    }

    private MessageEmbed createDebugEmbed(String message) {
        return new EmbedBuilder()
                .setTitle("Debug Information")
                .setDescription(message)
                .setColor(Color.RED) // Optionally set a color for emphasis
                .build();
    }
}
