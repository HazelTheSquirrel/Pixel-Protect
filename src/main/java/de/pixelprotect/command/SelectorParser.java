package de.pixelprotect.command;

import de.pixelprotect.model.ActionType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** PixelProtect query grammar. */
public final class SelectorParser {
    private SelectorParser() {}
    public static Parsed parse(List<String> tokens,int defaultRadius,int defaultHours,int maxRadius,int maxHours){
        int radius=defaultRadius,hours=defaultHours,page=1;String user=null,world=null;Integer x=null,y=null,z=null,chunkX=null,chunkZ=null;boolean count=false,preview=false;List<String>errors=new ArrayList<>();Set<ActionType>inc=new LinkedHashSet<>(),exc=new LinkedHashSet<>();Set<String>ib=new LinkedHashSet<>(),eb=new LinkedHashSet<>();
        for(String token:tokens){if(token==null||token.isBlank())continue;String v=token.trim();if(v.equalsIgnoreCase("#count")){count=true;continue;}if(v.equalsIgnoreCase("#preview")){preview=true;continue;}if(v.toLowerCase(Locale.ROOT).startsWith("#page:")){try{page=Integer.parseInt(v.substring(6));}catch(NumberFormatException e){errors.add("Ungültige Seite: "+v);}continue;}int c=v.indexOf(':');if(c<=0||c==v.length()-1){errors.add("Ungültiger Selektor: "+v);continue;}String key=v.substring(0,c).toLowerCase(Locale.ROOT),raw=v.substring(c+1).trim();try{switch(key){case "u"->user=raw;case "t"->hours=parseDurationHours(raw);case "r"->radius=Integer.parseInt(raw);case "a"->addActions(raw,inc,exc);case "i"->addValues(raw,ib);case "e"->addValues(raw,eb);case "w"->world=raw;case "c"->{int[]p=parseCoordinates(raw);x=p[0];y=p[1];z=p[2];chunkX=null;chunkZ=null;}case "ch","chunk"->{String[]p=raw.split(",");if(p.length!=2)throw new IllegalArgumentException("Chunk muss x,z sein.");chunkX=Integer.parseInt(p[0].trim());chunkZ=Integer.parseInt(p[1].trim());x=null;y=null;z=null;}default->errors.add("Unbekannter Selektor: "+key+":");}}catch(IllegalArgumentException e){errors.add(e.getMessage()==null?"Ungültiger Selektor: "+v:e.getMessage());}}
        if(radius<1||radius>maxRadius)errors.add("Radius muss zwischen 1 und "+maxRadius+" liegen.");if(hours<1||hours>maxHours)errors.add("Zeitfenster muss zwischen 1 und "+maxHours+" Stunden liegen.");if(page<1)errors.add("Seite muss mindestens 1 sein.");if(world!=null&&!world.matches("[A-Za-z0-9_.-]+"))errors.add("Ungültiger Weltname: "+world);return new Parsed(radius,hours,user,world,x,y,z,chunkX,chunkZ,inc,exc,ib,eb,count,preview,page,errors);
    }
    private static int[] parseCoordinates(String raw){String[]p=raw.split(",");if(p.length!=3)throw new IllegalArgumentException("Koordinaten müssen x,y,z sein.");try{return new int[]{Integer.parseInt(p[0].trim()),Integer.parseInt(p[1].trim()),Integer.parseInt(p[2].trim())};}catch(NumberFormatException e){throw new IllegalArgumentException("Ungültige Koordinaten: "+raw);}}
    private static void addActions(String raw,Set<ActionType>include,Set<ActionType>exclude){for(String part:raw.split(",")){String v=part.trim();if(v.isEmpty())continue;boolean neg=v.startsWith("-");try{(neg?exclude:include).add(ActionType.valueOf((neg?v.substring(1):v).toUpperCase(Locale.ROOT)));}catch(IllegalArgumentException e){throw new IllegalArgumentException("Unbekannte Aktion: "+v);}}}
    private static void addValues(String raw,Set<String>target){for(String part:raw.split(",")){String v=part.trim().toLowerCase(Locale.ROOT);if(!v.matches("[a-z0-9_./:-]+"))throw new IllegalArgumentException("Ungültiger Blockfilter: "+part.trim());target.add(v.startsWith("minecraft:")?v:"minecraft:"+v);}}
    private static int parseDurationHours(String raw){String v=raw.trim().toLowerCase(Locale.ROOT);try{if(v.matches("\\d+"))return Integer.parseInt(v);char s=v.charAt(v.length()-1);long n=Long.parseLong(v.substring(0,v.length()-1));Duration d=switch(s){case 's'->Duration.ofSeconds(n);case 'm'->Duration.ofMinutes(n);case 'h'->Duration.ofHours(n);case 'd'->Duration.ofDays(n);default->throw new IllegalArgumentException("Zeitangabe muss z.B. 30s, 30m, 12h oder 7d sein.");};return Math.toIntExact((d.toMinutes()+59)/60);}catch(ArithmeticException|NumberFormatException e){throw new IllegalArgumentException("Ungültige Zeitangabe: "+raw);}}
    public record Parsed(int radius,int hours,String user,String world,Integer x,Integer y,Integer z,Integer chunkX,Integer chunkZ,Set<ActionType>includeActions,Set<ActionType>excludeActions,Set<String>includeBlocks,Set<String>excludeBlocks,boolean countOnly,boolean preview,int page,List<String>errors){}
}
