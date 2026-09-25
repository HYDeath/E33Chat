package me.arasple.mc.trchat.module.internal.filter;

/**
 * @author Arasple
 * @since 2019/9/12 17:40
 */
public class FilteredObject {

    private final String filtered;

    private final int sensitiveWords;

    public FilteredObject(String filtered, int sensitiveWords) {
        this.filtered = filtered;
        this.sensitiveWords = sensitiveWords;
    }

    public String getFiltered() {
        return filtered;
    }

    public int getSensitiveWords() {
        return sensitiveWords;
    }
}