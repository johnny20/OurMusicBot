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
package com.jagrosh.jmusicbot.audio;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import com.jagrosh.jmusicbot.JMusicBot;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import com.jagrosh.jmusicbot.queue.FairQueue;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class AudioHandler extends AudioEventAdapter implements AudioSendHandler 
{
    private final FairQueue<QueuedTrack> queue = new FairQueue<>();
    private final List<AudioTrack> defaultQueue = new LinkedList<>();
    private final Set<String> votes = new HashSet<>();
    
    private final PlayerManager manager;
    private final AudioPlayer audioPlayer;
    private final long guildId;
    
    private AudioFrame lastFrame;

    protected AudioHandler(PlayerManager manager, Guild guild, AudioPlayer player)
    {
        this.manager = manager;
        this.audioPlayer = player;
        this.guildId = guild.getIdLong();
    }

    public int addTrackToFront(QueuedTrack qtrack)
    {
        if(this.audioPlayer.getPlayingTrack()==null)
        {
            this.audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }
        else
        {
            queue.addAt(0, qtrack);
            return 0;
        }
    }
    
    public int addTrack(QueuedTrack qtrack)
    {
        if(this.audioPlayer.getPlayingTrack()==null)
        {
            this.audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }
        else
            return queue.add(qtrack);
    }
    
    public FairQueue<QueuedTrack> getQueue()
    {
        return queue;
    }
    
    public void stopAndClear()
    {
        queue.clear();
        defaultQueue.clear();
        this.audioPlayer.stopTrack();        
    }
    
    public boolean isMusicPlaying(JDA jda)
    {
        return guild(jda).getSelfMember().getVoiceState().inVoiceChannel() && this.audioPlayer.getPlayingTrack()!=null;
    }
    
    public Set<String> getVotes()
    {
        return votes;
    }
    
    public AudioPlayer getPlayer()
    {
        return this.audioPlayer;
    }
    
    public long getRequester()
    {
        if(this.audioPlayer.getPlayingTrack()==null || this.audioPlayer.getPlayingTrack().getUserData(Long.class)==null)
            return 0;
        return this.audioPlayer.getPlayingTrack().getUserData(Long.class);
    }
    
    public boolean playFromDefault()
    {
        if(!defaultQueue.isEmpty())
        {
            this.audioPlayer.playTrack(defaultQueue.remove(0));
            return true;
        }
        Settings settings = manager.getBot().getSettingsManager().getSettings(guildId);
        if(settings==null || settings.getDefaultPlaylist()==null)
            return false;
        
        Playlist pl = manager.getBot().getPlaylistLoader().getPlaylist(settings.getDefaultPlaylist());
        if(pl==null || pl.getItems().isEmpty())
            return false;
        pl.loadTracks(this.manager, at -> 
        {
            if(this.audioPlayer.getPlayingTrack()==null)
                this.audioPlayer.playTrack(at);
            else
                defaultQueue.add(at);
        }, () -> 
        {
            if(pl.getTracks().isEmpty() && !this.manager.getBot().getConfig().getStay())
                this.manager.getBot().closeAudioConnection(guildId);
        });
        return true;
    }
    
    // Audio Events
    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) 
    {
        // if the track ended normally, and we're in repeat mode, re-add it to the queue
        if(endReason==AudioTrackEndReason.FINISHED && this.manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode())
        {
            queue.add(new QueuedTrack(track.makeClone(), track.getUserData(Long.class)==null ? 0L : track.getUserData(Long.class)));
        }
        
        if(queue.isEmpty())
        {
            if(!playFromDefault())
            {
            	this.manager.getBot().onTrackUpdate(guildId, null, this);
                if(!this.manager.getBot().getConfig().getStay())
                    this.manager.getBot().closeAudioConnection(guildId);
                // unpause, in the case when the player was paused and the track has been skipped.
                // this is to prevent the player being paused next time it's being used.
                player.setPaused(false);
            }
        }
        else
        {
            QueuedTrack qt = queue.pull();
            player.playTrack(qt.getTrack());
        }
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) 
    {
        votes.clear();
        this.manager.getBot().onTrackUpdate(guildId, track, this);
    }

    
    // Formatting
    public Message getNowPlaying(JDA jda)
    {
        if(isMusicPlaying(jda)) {
            Guild guild = guild(jda);
            AudioTrack track = this.audioPlayer.getPlayingTrack();
            MessageBuilder mb = new MessageBuilder();
            EmbedBuilder eb = this.initializeEmbed(guild, track);
            
            mb.append(FormatUtil.filter(this.manager.getBot().getConfig().getSuccess()+" **Now Playing in "+guild.getSelfMember().getVoiceState().getChannel().getName()+"...**"));
            return mb.setEmbed(eb.build()).build();
        }else
        	return null;
    }
    
    private EmbedBuilder initializeEmbed(Guild guild, AudioTrack track) {
    	EmbedBuilder eb = new EmbedBuilder();
    	this.setColor(guild, eb);
        this.setAuthor(guild, eb);
        this.setTitle(track, eb);
        this.setThumbnail(track, eb);
        this.setFooter(track, eb);
        this.setDescription(track, eb);
        return eb;
    }
    
    private void setDescription(AudioTrack track, EmbedBuilder eb) {
    	double progress = (double)this.audioPlayer.getPlayingTrack().getPosition()/track.getDuration();
    	eb.setDescription((this.audioPlayer.isPaused() ? JMusicBot.PAUSE_EMOJI : JMusicBot.PLAY_EMOJI)
                + " "+FormatUtil.progressBar(progress)
                + " `[" + FormatUtil.formatTime(track.getPosition()) + "/" + FormatUtil.formatTime(track.getDuration()) + "]` "
                + FormatUtil.volumeIcon(this.audioPlayer.getVolume()));
    }
    private void setFooter(AudioTrack track, EmbedBuilder eb) {
    	if(track.getInfo().author != null && !track.getInfo().author.isEmpty())
            eb.setFooter("Source: " + track.getInfo().author, null);
    }
    private void setColor(Guild guild, EmbedBuilder eb) {
    	eb.setColor(guild.getSelfMember().getColor());
    }
    private void setAuthor(Guild guild, EmbedBuilder eb) {
    	 if(this.getRequester() != 0)
         {
             User u = guild.getJDA().getUserById(this.getRequester());
             if(u==null)
                 eb.setAuthor("Unknown (ID:"+this.getRequester()+")", null, null);
             else
                 eb.setAuthor(u.getName()+"#"+u.getDiscriminator(), null, u.getEffectiveAvatarUrl());
         }
    }
    private void setTitle(AudioTrack track, EmbedBuilder eb) {
    	 try 
         {
             eb.setTitle(track.getInfo().title, track.getInfo().uri);
         }catch(Exception e) 
         {
             eb.setTitle(track.getInfo().title);
         }
    }
    private void setThumbnail(AudioTrack track, EmbedBuilder eb) {
    	 if(track instanceof YoutubeAudioTrack && this.manager.getBot().getConfig().useNPImages())
         {
             eb.setThumbnail("https://img.youtube.com/vi/"+track.getIdentifier()+"/mqdefault.jpg");
         }
    }
    
    public Message getNoMusicPlaying(JDA jda)
    {
        Guild guild = guild(jda);
        return new MessageBuilder()
                .setContent(FormatUtil.filter(this.manager.getBot().getConfig().getSuccess()+" **Now Playing...**"))
                .setEmbed(new EmbedBuilder()
                .setTitle("No music playing")
                .setDescription(JMusicBot.STOP_EMOJI+" "+FormatUtil.progressBar(-1)+" "+FormatUtil.volumeIcon(this.audioPlayer.getVolume()))
                .setColor(guild.getSelfMember().getColor())
                .build()).build();
    }
    
    public String getTopicFormat(JDA jda)
    {
        if(isMusicPlaying(jda))
        {
            long userid = getRequester();
            AudioTrack track = this.audioPlayer.getPlayingTrack();
            String title = track.getInfo().title;
            if(title==null || title.equals("Unknown Title"))
                title = track.getInfo().uri;
            return "**"+title+"** ["+(userid==0 ? "autoplay" : "<@"+userid+">")+"]"
                    + "\n" + (this.audioPlayer.isPaused() ? JMusicBot.PAUSE_EMOJI : JMusicBot.PLAY_EMOJI) + " "
                    + "[" + FormatUtil.formatTime(track.getDuration()) + "] "
                    + FormatUtil.volumeIcon(this.audioPlayer.getVolume());
        }
        else return "No music playing " + JMusicBot.STOP_EMOJI + " " + FormatUtil.volumeIcon(this.audioPlayer.getVolume());
    }
        
    @Override
    public boolean canProvide() 
    {
        lastFrame = this.audioPlayer.provide();
        return lastFrame != null;
    }

    @Override
    public ByteBuffer provide20MsAudio() 
    {
        return ByteBuffer.wrap(lastFrame.getData());
    }

    @Override
    public boolean isOpus() 
    {
        return true;
    }
    
    
    // Private methods
    private Guild guild(JDA jda)
    {
        return jda.getGuildById(guildId);
    }
}
