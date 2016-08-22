
/*
 * Copyright 2016 jagrosh.
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
package spectramusic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.Permission;
import net.dv8tion.jda.entities.Guild;
import net.dv8tion.jda.entities.Message;
import net.dv8tion.jda.entities.Role;
import net.dv8tion.jda.entities.TextChannel;
import net.dv8tion.jda.entities.VoiceChannel;
import net.dv8tion.jda.entities.VoiceStatus;
import net.dv8tion.jda.events.ReadyEvent;
import net.dv8tion.jda.events.ShutdownEvent;
import net.dv8tion.jda.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.hooks.ListenerAdapter;
import net.dv8tion.jda.managers.AudioManager;
import net.dv8tion.jda.player.hooks.PlayerListenerAdapter;
import net.dv8tion.jda.player.hooks.events.FinishEvent;
import net.dv8tion.jda.player.source.AudioInfo;
import net.dv8tion.jda.player.source.AudioSource;
import net.dv8tion.jda.utils.PermissionUtil;
import org.json.JSONObject;
import spectramusic.Command.PermLevel;
import spectramusic.commands.NowplayingCmd;
import spectramusic.commands.PlayCmd;
import spectramusic.commands.QueueCmd;
import spectramusic.commands.SearchCmd;
import spectramusic.commands.SetDJCmd;
import spectramusic.commands.SetTCCmd;
import spectramusic.commands.SetVCCmd;
import spectramusic.commands.ShutdownCmd;
import spectramusic.commands.SkipCmd;
import spectramusic.commands.StopCmd;
import spectramusic.commands.VolumeCmd;
import spectramusic.commands.VoteskipCmd;
import spectramusic.entities.ClumpedMusicPlayer;
import spectramusic.util.FormatUtil;

/**
 *
 * @author John Grosh (jagrosh)
 */
public class Bot extends ListenerAdapter {
    
    private final Command[] commands;
    private final String[] prefixes;
    private final String ownerId;
    private final JSONObject serverSettings;
    private final ArrayList<PlayerEvents> listeners = new ArrayList<>();
    private final HashMap<String,WaitingSearch> searches = new HashMap<>();
    
    public Bot(String ownerId, String[] prefixes){
        this.prefixes = prefixes;
        this.ownerId = ownerId;
        JSONObject loadedSettings = new JSONObject();
        try {
            loadedSettings = new JSONObject(new String(Files.readAllBytes(Paths.get("serversettings.json"))));
        } catch (IOException ex) {
            System.out.println("No server settings found; using new settings for all servers.");
        }
        serverSettings = loadedSettings;
        commands = new Command[]{
            new NowplayingCmd(),
            new PlayCmd(),
            new QueueCmd(),
            new SearchCmd(this),
            new VoteskipCmd(),
            
            new SkipCmd(),
            new StopCmd(),
            new VolumeCmd(),
            
            new SetDJCmd(serverSettings),
            new SetTCCmd(serverSettings),
            new SetVCCmd(serverSettings),
            
            new ShutdownCmd(),
        };
    }

    @Override
    public void onReady(ReadyEvent event) {
        event.getJDA().getAccountManager().setGame("Type %musichelp");
    }

    @Override
    public void onGuildMessageReceived(GuildMessageReceivedEvent event) {
        AudioSource searchresult = pullSearch(event);
        if(searchresult!=null)
        {
            AudioInfo info = searchresult.getInfo();
            if (info.getError() == null)
            {
                if(!event.getGuild().getVoiceStatusOfUser(event.getJDA().getSelfInfo()).inVoiceChannel())
                {
                    VoiceChannel target = event.getGuild().getVoiceStatusOfUser(event.getAuthor()).getChannel();
                    if(!target.checkPermission(event.getJDA().getSelfInfo(), Permission.VOICE_CONNECT) || !target.checkPermission(event.getJDA().getSelfInfo(), Permission.VOICE_SPEAK))
                    {
                        Sender.sendReply(SpConst.ERROR+"I must be able to connect and speak in **"+target.getName()+"** to join!", event);
                        return;
                    }
                    event.getGuild().getAudioManager().openAudioConnection(target);
                }
                ClumpedMusicPlayer player = (ClumpedMusicPlayer)event.getGuild().getAudioManager().getSendingHandler();
                int position = player.getAudioQueue().add(event.getAuthor().getId(),searchresult);
                if(player.isStopped())
                    player.play();
                Sender.sendReply(SpConst.SUCCESS+"Added **"+info.getTitle()
                        +"** (`"+(info.isLive() ? "LIVE" : info.getDuration().getTimestamp())+"`) to the queue "
                        +(position==0 ? "and will begin playing" :"at position "+(position+1)), event);
            }
            else
            {
                Sender.sendReply(SpConst.ERROR+"There was a problem with the provided source:\n"+info.getError(), event);
            }
            return;
        }
        
        String content = null;
        for(String prefix : prefixes)
            if(event.getMessage().getRawContent().toLowerCase().startsWith(prefix))
            {
                content = event.getMessage().getRawContent().substring(prefix.length()).trim();
                break;
            }
        if(content==null)
            return;
        
        //get levels for users
        JSONObject settings = serverSettings.has(event.getGuild().getId()) ? serverSettings.getJSONObject(event.getGuild().getId()) : null;
        Role djRole = settings==null ? null : event.getGuild().getRoleById(settings.getString("dj_role_id"));
        PermLevel userLevel = PermLevel.EVERYONE;
        if(event.getAuthor().getId().equals(ownerId))
            userLevel = PermLevel.OWNER;
        else if (PermissionUtil.checkPermission(event.getAuthor(), Permission.MANAGE_SERVER, event.getGuild()))
            userLevel = PermLevel.ADMIN;
        else if (djRole!=null && event.getGuild().getRolesForUser(event.getAuthor()).contains(djRole))
            userLevel = PermLevel.DJ;
        
        if(content.equalsIgnoreCase("musichelp") || content.equalsIgnoreCase("music help"))
        {
            StringBuilder builder = new StringBuilder("**Spectra Music** commands:");
            PermLevel current = PermLevel.EVERYONE;
            for(Command cmd: commands)
            {
                if(!current.isAtLeast(cmd.level))
                {
                    if(userLevel.isAtLeast(cmd.level))
                    {
                        current = cmd.level;
                        builder.append("\n\nCommands for **").append(cmd.level).append("**:");
                    }
                    else break;
                }
                builder.append("\n`").append(prefixes[0]).append(cmd.command)
                        .append(cmd.arguments==null ? "" : " "+cmd.arguments).append("` - ").append(cmd.getHelp());
            }
            Sender.sendReply(builder.toString(), event);
            return;
        }
        String[] parts = content.split("\\s+",2);
        Command command = null;
        for(Command cmd : commands)
            if(cmd.isCommandFor(parts[0]))
                command = cmd;
        if(command==null)
            return;
        
        boolean isValidChannel = userLevel.isAtLeast(PermLevel.DJ) || settings==null || settings.getString("text_channel_id").equals("") || settings.getString("text_channel_id").equals(event.getChannel().getId());
        
        boolean listeningInVc;
        VoiceStatus botstatus = event.getGuild().getVoiceStatusOfUser(event.getJDA().getSelfInfo());
        VoiceStatus userstatus = event.getGuild().getVoiceStatusOfUser(event.getAuthor());
        VoiceChannel vc = settings==null ? null : event.getJDA().getVoiceChannelById(settings.getString("voice_channel_id"));
        if(userstatus==null || !userstatus.inVoiceChannel() || userstatus.isDeaf())
        {
            listeningInVc = false;
        }
        else if (botstatus==null || !botstatus.inVoiceChannel())
        {
            listeningInVc = vc==null || userstatus.getChannel().equals(vc);
        }
        else
        {
            listeningInVc = botstatus.getChannel().equals(userstatus.getChannel());
        }
        
        if(isValidChannel)
        {
            AudioManager manager = event.getGuild().getAudioManager();
            ClumpedMusicPlayer player;
            if (manager.getSendingHandler() == null)
            {
                player = new ClumpedMusicPlayer();
                PlayerEvents events = new PlayerEvents(event.getGuild());
                player.addEventListener(events);
                listeners.add(events);
                player.setVolume(.35f);
                manager.setSendingHandler(player);
            }
            else
            {
                player = (ClumpedMusicPlayer) manager.getSendingHandler();
            }
            command.run(parts.length<2||parts[1]==null ? "" : parts[1], event, userLevel, player, listeningInVc);
        }
    }

    @Override
    public void onShutdown(ShutdownEvent event) {
        listeners.stream().forEach(e -> e.shutdown());
        Sender.shutdown();
    }
    
    public void addSearch(GuildMessageReceivedEvent event, List<AudioSource> list, Message botMessage)
    {
        searches.put(event.getAuthor().getId()+"|"+event.getChannel().getId(), new WaitingSearch(list,event.getMessage(),botMessage));
    }
    
    public AudioSource pullSearch(GuildMessageReceivedEvent event)
    {
        WaitingSearch search = searches.remove(event.getAuthor().getId()+"|"+event.getChannel().getId());
        if(search==null)
            return null;
        search.botMessage.deleteMessage();
        if(event.getChannel().checkPermission(event.getJDA().getSelfInfo(), Permission.MESSAGE_MANAGE))
            search.userMessage.deleteMessage();
        try
        {
            return search.list.get(Integer.parseInt(event.getMessage().getRawContent())-1);
        }
        catch(Exception e)
        {
            return null;
        }
    }
    
    private class WaitingSearch {
        private final List<AudioSource> list;
        private final Message userMessage;
        private final Message botMessage;
        public WaitingSearch(List<AudioSource> list, Message userMessage, Message botMessage)
        {
            this.list = list;
            this.userMessage = userMessage;
            this.botMessage = botMessage;
        }
    }
    
    private class PlayerEvents extends PlayerListenerAdapter {
        private final Guild guild;
        ScheduledExecutorService channelUpdater = Executors.newScheduledThreadPool(1);
        
        private PlayerEvents(Guild guild)
        {
            this.guild = guild;
            channelUpdater.scheduleWithFixedDelay(() -> {
                JSONObject settings = serverSettings.has(guild.getId()) ? serverSettings.getJSONObject(guild.getId()) : null;
                if(settings!=null)
                {
                    TextChannel channel = guild.getJDA().getTextChannelById(settings.getString("text_channel_id"));
                    if(channel!=null && channel.checkPermission(guild.getJDA().getSelfInfo(), Permission.MANAGE_CHANNEL))
                    {
                        String otherText;
                        if(channel.getTopic()!=null && channel.getTopic().contains("\u200B"))
                        {
                            otherText = channel.getTopic().substring(channel.getTopic().indexOf("\u200B")+1);
                        }
                        else
                            otherText = channel.getTopic()==null ? "" : channel.getTopic();
                        String newTopic = FormatUtil.formattedAudio((ClumpedMusicPlayer)guild.getAudioManager().getSendingHandler(), guild.getJDA(), true)+"\n\u200B"+otherText;
                        if(!newTopic.equals(channel.getTopic()))
                            channel.getManager().setTopic(newTopic).update();
                    }
                }
            }, 0, 10, TimeUnit.SECONDS);
        }
        
        public void shutdown()
        {
            channelUpdater.shutdown();
        }
        
        @Override
        public void onFinish(FinishEvent event) {
            guild.getAudioManager().closeAudioConnection();
        }
    }
}
