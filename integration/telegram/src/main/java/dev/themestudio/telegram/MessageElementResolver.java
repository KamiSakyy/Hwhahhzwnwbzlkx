package dev.themestudio.telegram;

import android.text.Spanned;
import android.text.StaticLayout;
import android.text.style.ClickableSpan;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.Cells.ChatMessageCell;

/** Uses measured Telegram layout, not guessed dp rectangles. Coordinates are cell-local. */
public final class MessageElementResolver {
    public enum Region { TEXT, CAPTION, LINK, BUBBLE }
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
        // During bubble transitions Telegram interpolates several private drawing offsets.
        // Don't guess a selectable region while those measurements are in motion.
        if (cell.getTransitionParams().animateBackgroundBoundsInner) return null;
        Hit text = resolveText(cell, message, message.textLayoutBlocks, message.messageText,
                localX - cell.getTextX(), y - cell.getTextY(), message.textXOffset, Region.TEXT);
        if (text != null) return text;
        MessageObject.TextLayoutBlocks caption = cell.getCaptionLayout();
        if (caption != null) {
            Hit captionHit = resolveText(cell, message, caption.textLayoutBlocks, message.caption,
                    localX - cell.getCaptionX(), y - cell.getCaptionY(), caption.textXOffset, Region.CAPTION);
            if (captionHit != null) return captionHit;
        }
        if (localX >= cell.getBackgroundDrawableLeft() && localX <= cell.getBackgroundDrawableRight()
                && y >= cell.getBackgroundDrawableTop() && y <= cell.getBackgroundDrawableBottom()) {
            return new Hit(cell, out ? "chat_outBubble" : "chat_inBubble", Region.BUBBLE);
        }
        return null;
    }

    private static Hit resolveText(ChatMessageCell cell, MessageObject message,
            java.util.ArrayList<MessageObject.TextLayoutBlock> blocks, CharSequence content,
            float x, float y, float rtlOffset, Region region) {
        if (blocks == null || content == null) return null;
        boolean out = message.isOutOwner();
        for (MessageObject.TextLayoutBlock block : blocks) {
            if (block.quote || block.code || block.textLayout == null) continue;
            StaticLayout layout = block.textLayout;
            float blockY = y - block.textYOffset(blocks, cell.getTransitionParams());
            float blockX = x + (block.isRtl() ? rtlOffset : 0);
            if (blockY < 0 || blockY >= layout.getHeight()) continue;
            int line = layout.getLineForVertical((int) blockY);
            if (blockX < layout.getLineLeft(line) || blockX > layout.getLineRight(line)) continue;
            int offset = block.charactersOffset + layout.getOffsetForHorizontal(line, blockX);
            if (content instanceof Spanned && offset >= 0 && offset < content.length()) {
                Spanned spans = (Spanned) content;
                for (ClickableSpan span : spans.getSpans(offset, offset, ClickableSpan.class)) {
                    if (spans.getSpanStart(span) <= offset && offset < spans.getSpanEnd(span)) {
                        return new Hit(cell, out ? "chat_messageLinkOut" : "chat_messageLinkIn", Region.LINK);
                    }
                }
            }
            return new Hit(cell, out ? "chat_messageTextOut" : "chat_messageTextIn", region);
        }
        return null;
    }

    private MessageElementResolver() {}
}
