package dev.aurora.core.builder;

import java.util.*;

/** Chooses one exact transfer that fits a real destination slot, reserving room for the next build action. */
public final class RestockBatch {
    public record Space(int slot,int capacity) { }
    public record Source(int slot,String item,int count,List<Space> destinations) {
        public Source {destinations=List.copyOf(destinations);}
    }
    public record Transfer(int source,int destination,String item,int count) { }
    private RestockBatch() { }
    public static Optional<Transfer> choose(List<Source> sources,Map<String,Integer> wanted,Map<String,Integer> own,String priority,int minimum) {
        boolean reservePriority=priority!=null && own.getOrDefault(priority,0)<minimum;
        var ordered=new ArrayList<>(sources);
        ordered.sort(Comparator.comparingInt((Source s)->Objects.equals(s.item(),priority)?0:1).thenComparingInt(Source::slot));
        for(var source:ordered) {
            if(reservePriority && !Objects.equals(priority,source.item()))continue;
            int wantedCount=Math.max(0,wanted.getOrDefault(source.item(),0)-own.getOrDefault(source.item(),0));
            for(var destination:source.destinations()) {
                int amount=Math.min(wantedCount,Math.min(source.count(),destination.capacity()));
                if(amount>0)return Optional.of(new Transfer(source.slot(),destination.slot(),source.item(),amount));
            }
        }
        return Optional.empty();
    }
    public static boolean minimumSatisfied(Map<String,Integer> own,String priority,int minimum) {
        return priority==null || own.getOrDefault(priority,0)>=minimum;
    }
}
