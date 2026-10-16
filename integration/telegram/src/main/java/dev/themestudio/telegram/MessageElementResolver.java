package dev.themestudio.telegram;

import android.text.Spanned;
import android.text.StaticLayout;
import android.text.style.ClickableSpan;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.Cells.ChatMessageCell;

/** Uses measured Telegram layout, not guessed dp rectangles. Coordinates are cell-local. */
public final class MessageElementResolver {
    public enum Region { TEXT, LINK, BUBBLE }
    public static final class Hit {
        public final ChatMessageCell cell;
        public final String key;
        public final Region region;
        private Hit(ChatMessageCell cell, String key, Region region) {
            this.cell = cell; this.key = key; this.region = region;
        }
    }

    public static Hit resolve(ChatMessageCell cell, float localX, float localY) {
        MessageObject message = cell.getMessageObject();
        if (message == null || !cell.isLaidOut()) return null;
        float y = localY - cell.getPaddingTopAnimated();
        boolean out = message.isOutOwner();
        if (message.textLayoutBlocks != null) {
            float textY = y - cell.getTextY();
            for (MessageObject.TextLayoutBlock block : message.textLayoutBlocks) {
                // Quoted/collapsed/code blocks need their own targets; don't label them plain text.
                if (block.quote || block.code || block.textLayout == null) continue;
                StaticLayout layout = block.textLayout;
                float blockY = textY - block.textYOffset(message.textLayoutBlocks, cell.getTransitionParams());
                float blockX = localX - cell.getTextX() + (block.isRtl() ? message.textXOffset : 0);
                if (blockY < 0 || blockY >= layout.getHeight()) continue;
                int line = layout.getLineForVertical((int) blockY);
                if (blockX < layout.getLineLeft(line) || blockX > layout.getLineRight(line)) continue;
                int offset = block.charactersOffset + layout.getOffsetForHorizontal(line, blockX);
                if (message.messageText instanceof Spanned) {
                    Spanned text = (Spanned) message.messageText;
                    if (offset >= 0 && offset < text.length()) {
                        for (ClickableSpan span : text.getSpans(offset, offset, ClickableSpan.class)) {
                            if (text.getSpanStart(span) <= offset && offset < text.getSpanEnd(span)) {
                                return new Hit(cell, out ? "chat_messageLinkOut" : "chat_messageLinkIn", Region.LINK);
                            }
                        }
                    }
                }
                return new Hit(cell, out ? "chat_messageTextOut" : "chat_messageTextIn", Region.TEXT);
            }
        }
        if (localX >= cell.getBackgroundDrawableLeft() && localX <= cell.getBackgroundDrawableRight()
                && y >= cell.getBackgroundDrawableTop() && y <= cell.getBackgroundDrawableBottom()) {
            return new Hit(cell, out ? "chat_outBubble" : "chat_inBubble", Region.BUBBLE);
        }
        return null;
    }

    private MessageElementResolver() {}
}
