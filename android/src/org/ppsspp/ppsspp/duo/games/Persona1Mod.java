package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;

import java.util.Arrays;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Persona 1 PSP companion screen. Uses the game's live party/battle memory data
 * and the original in-game face portraits, with a custom SMT IV-inspired layout.
 */
public final class Persona1Mod extends DuoMod {
    public static final String ID = "persona1";
    private static final String[] GAME_IDS = {"ULUS10432"};

    // Verified for Persona 1 PSP USA ULUS10432 v1.00.
    private static final int FLAG = 0x08B967BC;
    private static final int FIELD = 8;
    private static final int PARTY = 0x08CC51F8;
    private static final int PARTY_SIZE = 0x80;
    private static final int UNITS = 0x08D03DC8;
    private static final int UNIT_SIZE = 0x118;
    private static final int PARTY_COUNT = 5;
    private static final int ENEMY_COUNT = 8;
    private static final int ID_OFFSET = 0x3E;
    private static final int NAME = 0x40;
    private static final int NAME_LEN = 0x24;
    private static final int LEVEL = 0x6A;
    private static final long HIT_EFFECT_MS = 450;

    private DuoModContext host;
    private Persona1View view;
    private long lastBattle;

    @Override public String getId() { return ID; }

    @Override public String getTitle(Context context) {
        return "Persona 1 Dual Screen";
    }

    @Override public String getDescription(Context context) {
        return "Dual-screen party status and game information for Persona 1.";
    }

    @Override public int getPriority(DuoStatus status) {
        return Arrays.asList(GAME_IDS).contains(status.gameId) ? 100 : -1;
    }

    @Override public long getStatusIntervalMs() { return 100; }

    @Override public View onCreateView(DuoModContext host) {
        this.host = host;
        view = new Persona1View(host.getContext());
        watch();
        Persona1Art.loadFaces(host, () -> {
            if (view != null) view.invalidate();
        });
        return view;
    }

    @Override public void onDestroyView() {
        view = null;
        host = null;
    }

    @Override public void onStatus(DuoStatus status) {
        if (view == null || host == null || (!status.isInGame() && !status.isPauseMenu())) return;
        byte[] flag = host.readMemoryWatch(0);
        byte[] party = host.readMemoryWatch(1);
        byte[] units = host.readMemoryWatch(2);
        if (flag == null || party == null || units == null) return;

        Unit[] enemies = null;
        if (u32(flag, 0) != FIELD) {
            enemies = readUnits(units, PARTY_COUNT, ENEMY_COUNT, UNIT_SIZE, true);
            boolean alive = false;
            for (Unit enemy : enemies) alive |= enemy.hp > 0;
            if (!alive) enemies = null;
        }

        if (enemies != null) {
            lastBattle = android.os.SystemClock.uptimeMillis();
            view.setUnits(readUnits(units, 0, PARTY_COUNT, UNIT_SIZE, false), enemies, true);
            for (Unit enemy : enemies) Persona1Art.loadDemon(host, enemy.id, () -> {
                if (view != null) view.invalidate();
            });
        } else if (android.os.SystemClock.uptimeMillis() - lastBattle > 1500) {
            // The saved party roster is current on the field; battle slots can lag after a
            // character joins or leaves. During battle, use the live battle slots above.
            view.setUnits(readUnits(party, 0, PARTY_COUNT, PARTY_SIZE, false), null, false);
        }
    }

    private void watch() {
        host.setMemoryWatches(
            new int[] {FLAG, PARTY, UNITS},
            new int[] {4, PARTY_SIZE * PARTY_COUNT, UNIT_SIZE * (PARTY_COUNT + ENEMY_COUNT)}
        );
    }

    private static final class Unit {
        String name;
        int id, level, hp, maxHp, sp, maxSp;
        boolean enemy;

        boolean same(Unit other) {
            return other != null && id == other.id && level == other.level
                && hp == other.hp && maxHp == other.maxHp && sp == other.sp
                && maxSp == other.maxSp && name.equals(other.name);
        }

        Bitmap portrait() {
            return enemy ? Persona1Art.demon(id) : Persona1Art.face(id);
        }
    }

    private static Unit[] readUnits(byte[] data, int first, int count, int size, boolean enemy) {
        Unit[] result = new Unit[count];
        int used = 0;
        for (int i = 0; i < count; i++) {
            int offset = (first + i) * size;
            Unit unit = new Unit();
            unit.hp = u32(data, offset);
            unit.maxHp = u32(data, offset + 4);
            unit.sp = u32(data, offset + 8);
            unit.maxSp = u32(data, offset + 0xC);
            if (unit.maxHp <= 0 || unit.maxHp > 9999 || unit.hp < 0 || unit.hp > unit.maxHp
                || unit.maxSp < 0 || unit.maxSp > 9999 || unit.sp < 0 || unit.sp > unit.maxSp) {
                continue;
            }
            unit.id = (data[offset + ID_OFFSET] & 0xFF) | ((data[offset + ID_OFFSET + 1] & 0xFF) << 8);
            unit.level = data[offset + LEVEL] & 0xFF;
            unit.name = decodeName(data, offset + NAME);
            unit.enemy = enemy;
            // On a fresh save, the protagonist's party-name field can temporarily
            // contain the game's "ATLUS" placeholder instead of the character name.
            // Treat that placeholder as Naoya so the correct label and HD portrait load.
            if (!enemy && "ATLUS".equalsIgnoreCase(unit.name)) {
                unit.name = "Naoya";
            }
            if (unit.name.isEmpty()) continue;
            result[used++] = unit;
        }
        return Arrays.copyOf(result, used);
    }

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static String decodeName(byte[] data, int offset) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i + 1 < NAME_LEN && offset + i + 1 < data.length; i += 2) {
            int ch = ((data[offset + i] & 0xFF) << 8) | (data[offset + i + 1] & 0xFF);
            if (ch == 0 || ch == 0xFFFF) break;
            if (ch == 0x8140) result.append(' ');
            else if (ch >= 0x101 && ch <= 0x11A) result.append((char) (ch - 0xA0));
            else if (ch >= 0xBF && ch < 0x100) result.append((char) (ch - 0x9F));
        }
        return result.toString().trim();
    }

    private static final class Persona1View extends View {
        private static final int BG = 0xFF17121D;
        private static final int PANEL = 0xFF6E4A86;
        private static final int PANEL_LIGHT = 0xFF8E55B8;
        private static final int PANEL_DARK = 0xFF251333;
        private static final int WHITE = 0xFFF3F1EA;
        private static final int BLACK = 0xFF100B16;
        private static final int GREEN = 0xFF9CFF20;
        private static final int HP_COLOR = 0xFFB7D83D;
        private static final int SP_COLOR = 0xFF50D2E7;
        private static final int DIM_OVERLAY = 0x775A5064;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint grayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Typeface font;
        private final Typeface boldFont;
        private Unit[] party = new Unit[0];
        private final long[] hitTimes = new long[PARTY_COUNT];
        private Unit[] enemies;
        private boolean battle;
        private int selected = 0;
        private boolean detailMode;
        private boolean showFullPortrait;
        private final Map<String, Bitmap> highResPortraits = new HashMap<>();
        private Unit fullPortraitUnit;
        private final ScaleGestureDetector scaleDetector;
        private float portraitZoom = 1f;
        private float portraitOffsetX, portraitOffsetY;
        private float portraitLastX, portraitLastY;
        private int portraitPointerId = MotionEvent.INVALID_POINTER_ID;
        private boolean portraitGestureUsed;
        private float scrollX;
        private float downX, downY, lastX;
        private float viewScale = 1f;
        private boolean dragging;
        private boolean moved;
        private static final float MARGIN = 5f;
        private static final float GAP = 4f;
        private static final float CARD_W = 58f;
        private static final float CARD_TOP = 5f;
        private static final float CARD_H = 168f;
        private static final float PANEL_TOP = 177f;
        private static final float PANEL_BOTTOM = 235f;

        Persona1View(Context context) {
            super(context);
            Typeface loaded;
            try {
                loaded = Typeface.createFromAsset(context.getAssets(), "Fontsona4Golden.ttf");
            } catch (RuntimeException | Error ignored) {
                loaded = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            }
            font = loaded;
            boldFont = Typeface.create(loaded, Typeface.BOLD);
            scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float oldZoom = portraitZoom;
                        float newZoom = Math.max(1f, Math.min(5f,
                            oldZoom * (float)Math.pow(detector.getScaleFactor(), 0.90)));
                        if (Math.abs(newZoom - oldZoom) < 0.0005f) return true;
                        float focusX = detector.getFocusX() / viewScale;
                        float focusY = detector.getFocusY() / viewScale;
                        float centerX = getWidth() / viewScale / 2f;
                        float centerY = 120f;
                        float ratio = newZoom / oldZoom;
                        portraitOffsetX = focusX - centerX -
                            (focusX - centerX - portraitOffsetX) * ratio;
                        portraitOffsetY = focusY - centerY -
                            (focusY - centerY - portraitOffsetY) * ratio;
                        portraitZoom = newZoom;
                        if (portraitZoom <= 1.001f) {
                            portraitZoom = 1f;
                            portraitOffsetX = portraitOffsetY = 0f;
                        } else {
                            clampPortraitOffsets();
                        }
                        portraitGestureUsed = true;
                        moved = true;
                        invalidate();
                        return true;
                    }
                });
            loadHighResPortraits(context);
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0);
            grayPaint.setColorFilter(new ColorMatrixColorFilter(matrix));
            setFocusable(true);
        }

        void setUnits(Unit[] newParty, Unit[] newEnemies, boolean inBattle) {
            long now = SystemClock.uptimeMillis();
            if (newParty != null) {
                for (int i = 0; i < Math.min(newParty.length, party.length); i++) {
                    Unit old = party[i];
                    Unit next = newParty[i];
                    if (old != null && next != null && old.id == next.id && next.hp < old.hp) {
                        hitTimes[i] = now;
                    }
                }
            }
            if (sameUnits(party, newParty) && sameUnits(enemies, newEnemies) && battle == inBattle) {
                if (hasActiveHitEffect()) postInvalidateOnAnimation();
                return;
            }
            party = newParty;
            enemies = newEnemies;
            battle = inBattle;
            if (selected >= party.length) selected = party.length - 1;
            if (selected < -1) selected = -1;
            postInvalidateOnAnimation();
        }

        private static boolean sameUnits(Unit[] a, Unit[] b) {
            if (a == null || b == null) return a == b;
            if (a.length != b.length) return false;
            for (int i = 0; i < a.length; i++) if (!a[i].same(b[i])) return false;
            return true;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float scale = h / 240f;
            viewScale = scale;
            canvas.save();
            canvas.scale(scale, scale);
            float logicalW = w / scale;
            canvas.drawColor(BG);
            drawBackground(canvas, logicalW, 240);
            if (showFullPortrait && fullPortraitUnit != null) {
                drawFullPortrait(canvas, logicalW, 240, fullPortraitUnit);
            } else {
                drawCardsViewport(canvas, logicalW);
                drawInfoPanel(canvas, logicalW);
            }
            canvas.restore();
            if (hasActiveHitEffect()) postInvalidateOnAnimation();
        }

        private boolean hasActiveHitEffect() {
            long now = SystemClock.uptimeMillis();
            for (long hit : hitTimes) {
                if (hit > 0 && now - hit < HIT_EFFECT_MS) return true;
            }
            return false;
        }

        private float shakeOffset(long since) {
            if (since < 0 || since >= HIT_EFFECT_MS) return 0f;
            float fade = 1f - since / (float) HIT_EFFECT_MS;
            return (float) Math.sin(since * 0.09) * 5.5f * fade;
        }

        private void loadHighResPortraits(Context context) {
            String[] files = {"Persona1Naoya.png", "Persona1Maki.png", "Persona1Kei.png", "Persona1Masao.png",
                "Persona1Reiji.png", "Persona1Eriko.png", "Persona1Hidehiko.png", "Persona1Yuka.png", "Persona1Yukino.png"};
            for (String file : files) {
                try (java.io.InputStream in = context.getAssets().open(file)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(in);
                    if (bitmap != null) highResPortraits.put(file, bitmap);
                } catch (IOException ignored) { }
            }
        }

        private Bitmap getPortrait(Unit unit) {
            if (unit.enemy) return unit.portrait();
            String n = unit.name == null ? "" : unit.name.toLowerCase(Locale.ROOT);
            String file = null;
            // The early new-game memory value may be ATLUS before readUnits normalizes it.
            if (n.equals("atlus")) file = "Persona1Naoya.png";
            else if (n.contains("maki")) file = "Persona1Maki.png";
            else if (n.contains("nanjo") || n.contains("kei")) file = "Persona1Kei.png";
            else if (n.contains("mark") || n.contains("masao")) file = "Persona1Masao.png";
            else if (n.contains("reiji")) file = "Persona1Reiji.png";
            else if (n.contains("elly") || n.contains("eriko")) file = "Persona1Eriko.png";
            else if (n.contains("brown") || n.contains("hidehiko")) file = "Persona1Hidehiko.png";
            else if (n.contains("yuka")) file = "Persona1Yuka.png";
            else if (n.contains("yukino")) file = "Persona1Yukino.png";
            else if (n.contains("nao") || n.contains("hero") || n.contains("protagonist")) file = "Persona1Naoya.png";
            Bitmap bitmap = file == null ? null : highResPortraits.get(file);
            return bitmap != null ? bitmap : unit.portrait();
        }

        private void drawFullPortrait(Canvas c, float w, float h, Unit unit) {
            c.drawColor(0xFF100B16);
            Bitmap bitmap = getPortrait(unit);
            if (bitmap != null) {
                float availableW = Math.max(1f, w - 12f);
                float availableH = Math.max(1f, h - 30f);
                float fit = Math.min(availableW / bitmap.getWidth(), availableH / bitmap.getHeight());
                float dw = bitmap.getWidth() * fit, dh = bitmap.getHeight() * fit;
                c.save();
                c.translate(w / 2f + portraitOffsetX, h / 2f + portraitOffsetY);
                c.scale(portraitZoom, portraitZoom);
                rect.set(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f);
                c.drawBitmap(bitmap, null, rect, paint);
                c.restore();
            }
            text(c, "PINCH TO ZOOM  /  TAP TO RETURN", w / 2f, h - 7f, 6.2f, WHITE, true, Paint.Align.CENTER);
        }

        private void drawBackground(Canvas c, float w, float h) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF2A2033);
            for (float y = 3; y < h; y += 6) {
                for (float x = ((int)(y * 2)) % 11; x < w; x += 11) c.drawCircle(x, y, 0.28f, paint);
            }
        }

        private void drawCardsViewport(Canvas c, float w) {
            c.save();
            c.clipRect(MARGIN, 0, w - MARGIN, PANEL_TOP - 3);
            float total = party.length * CARD_W + Math.max(0, party.length - 1) * GAP;
            float maxScroll = Math.max(0, total - (w - MARGIN * 2));
            scrollX = Math.max(0, Math.min(scrollX, maxScroll));
            long now = SystemClock.uptimeMillis();
            for (int i = 0; i < party.length; i++) {
                float x = MARGIN + i * (CARD_W + GAP) - scrollX;
                long since = hitTimes[i] == 0 ? HIT_EFFECT_MS : now - hitTimes[i];
                x += shakeOffset(since);
                drawCard(c, party[i], x, CARD_TOP, CARD_W, CARD_H, i == selected);
                if (since >= 0 && since < HIT_EFFECT_MS) {
                    int alpha = (int) (110 * (1f - since / (float) HIT_EFFECT_MS));
                    fill(c, x, 25, x + CARD_W, 25 + CARD_H, (alpha << 24) | 0x00E5392F);
                }
            }
            c.restore();
            if (maxScroll > 0) {
                float trackL = MARGIN, trackR = w - MARGIN, y = 173.2f;
                fill(c, trackL, y, trackR, y + 1.3f, 0xFF454A4B);
                float thumbW = (trackR - trackL) * ((trackR - trackL) / total);
                float thumbX = trackL + (trackR - trackL - thumbW) * (scrollX / maxScroll);
                fill(c, thumbX, y, thumbX + thumbW, y + 1.3f, 0xFFB9C0C0);
            }
        }

        private void drawCard(Canvas c, Unit unit, float x, float y, float w, float h, boolean active) {
            boolean down = unit.hp == 0;
            float right = x + w, bottom = y + h;
            fill(c, x, y, right, bottom, 0xFF100B16);
            stroke(c, x + 0.7f, y + 0.7f, right - 0.7f, bottom - 0.7f, 0xFF6E7475, 0.7f);
            float left = x + 1.5f, innerR = right - 1.5f;
            fill(c, left, y + 1.5f, innerR, y + 17, active ? PANEL_LIGHT : 0xFF4B365B);
            text(c, unit.name, x + w / 2, y + 12, fit(unit.name, w - 8, 9), WHITE, true, Paint.Align.CENTER);

            drawStat(c, "HP", unit.hp, unit.maxHp, HP_COLOR, left, y + 18, innerR, 19);
            drawStat(c, "SP", unit.sp, unit.maxSp, SP_COLOR, left, y + 37, innerR, 19);

            float imageTop = y + 56, imageBottom = bottom - 1.5f;
            fill(c, left, imageTop, innerR, imageBottom, down ? 0xFF777777 : (unit.enemy ? 0xFF6B536F : 0xFF382447));
            Bitmap portrait = getPortrait(unit);
            if (portrait != null) {
                c.save();
                c.clipRect(left, imageTop, innerR, imageBottom);
                float iw = innerR - left, ih = imageBottom - imageTop;
                if (!unit.enemy && highResPortraits.containsValue(portrait)) {
                    // Use the upper half of the source art for the normal card.
                    // Mark's face sits toward the right side of his artwork, so use a
                    // right-aligned source crop instead of shifting the destination image
                    // (which was pushing his face farther out of frame).
                    int halfHeight = Math.max(1, portrait.getHeight() / 2);
                    boolean mark = unit.name != null && (unit.name.equalsIgnoreCase("MARK") || unit.name.equalsIgnoreCase("MASAO"));
                    float targetRatio = iw / ih;

                    // Keep the source crop at the same aspect ratio as the card image area.
                    // Some character art (including Elly's) is narrower than the others.
                    // Previously the crop width was clamped to the bitmap width but its
                    // height stayed at halfHeight, which stretched narrow portraits sideways.
                    int cropWidth;
                    int cropHeight;
                    if ((float) portrait.getWidth() / halfHeight < targetRatio) {
                        cropWidth = portrait.getWidth();
                        cropHeight = Math.max(1, Math.min(halfHeight,
                            Math.round(cropWidth / targetRatio)));
                    } else {
                        cropHeight = halfHeight;
                        cropWidth = Math.max(1, Math.min(portrait.getWidth(),
                            Math.round(cropHeight * targetRatio)));
                    }

                    // Mark's face sits toward the right side of his artwork, so retain
                    // the existing special horizontal framing for him.
                    int cropLeft;
                    if (mark) {
                        int leftShift = Math.round(cropWidth * 0.34f);
                        cropLeft = Math.max(0, portrait.getWidth() - cropWidth - leftShift);
                    } else {
                        cropLeft = (portrait.getWidth() - cropWidth) / 2;
                    }
                    cropLeft = Math.min(cropLeft, portrait.getWidth() - cropWidth);
                    android.graphics.Rect src = new android.graphics.Rect(
                        cropLeft, 0, cropLeft + cropWidth, cropHeight);
                    rect.set(left, imageTop, innerR, imageBottom);
                    c.drawBitmap(portrait, src, rect, down ? grayPaint : paint);
                } else {
                    float factor = unit.enemy
                        ? Math.min(iw * 0.92f / portrait.getWidth(), ih * 0.9f / portrait.getHeight())
                        : Math.max(iw / portrait.getWidth(), ih / portrait.getHeight());
                    float dw = portrait.getWidth() * factor, dh = portrait.getHeight() * factor;
                    if (unit.enemy) rect.set(left + (iw-dw)/2, imageTop + (ih-dh)/2, left + (iw+dw)/2, imageTop + (ih+dh)/2);
                    else rect.set(left + (iw-dw)/2, imageBottom-dh, left + (iw+dw)/2, imageBottom);
                    c.drawBitmap(portrait, null, rect, down ? grayPaint : paint);
                }
                c.restore();
            } else {
                text(c, unit.enemy ? "?" : initials(unit.name), x + w/2, imageTop + (imageBottom-imageTop)*0.55f,
                    20, 0xFF202426, true, Paint.Align.CENTER);
            }
            if (unit.level > 0) {
                fill(c, left, bottom - 14, left + 36, bottom, 0xD8101014);
                text(c, "Lv." + unit.level, left + 3, bottom - 4, 8, WHITE, true, Paint.Align.LEFT);
            }
            if (down) fill(c, x, y, right, bottom, 0x66707070);
            if (!active) fill(c, x, y, right, bottom, DIM_OVERLAY);
            else {
                outline(c, x - 1, y - 1, right + 1, bottom + 1, 0x339CFF20, 2.2f);
                outline(c, x - 0.2f, y - 0.2f, right + 0.2f, bottom + 0.2f, GREEN, 1.1f);
                outline(c, x + 1.2f, y + 1.2f, right - 1.2f, bottom - 1.2f, 0xFFCBFF85, 0.35f);
            }
        }

        private void drawStat(Canvas c, String label, int value, int max, int color, float l, float y, float r, float rowH) {
            fill(c, l, y, r, y + rowH, 0xFF21152B);
            float labelW = 18;
            // SMT IV-style labels: colored letters with a dark outline, not colored boxes.
            outlineText(c, label, l + 3, y + rowH - 5, 7.2f, color, true, Paint.Align.LEFT);
            text(c, value + "/" + max, r - 3, y + 8, 6.8f, WHITE, false, Paint.Align.RIGHT);
            float barY = y + 11;
            float barH = 4;
            float barW = r - l - labelW - 6;
            float bx = l + labelW + 3;
            fill(c, bx, barY, bx + barW, barY + barH, 0xFF777D7E);
            float fraction = max <= 0 ? 0 : Math.max(0, Math.min(1, value / (float)max));
            if (fraction > 0) fill(c, bx, barY, bx + barW * fraction, barY + barH, color);
        }

        private void drawInfoPanel(Canvas c, float w) {
            float top = PANEL_TOP, bottom = PANEL_BOTTOM;
            fill(c, 5, top, w - 5, bottom, 0xFF2D173F);
            outline(c, 5, top, w - 5, bottom, 0xFF9B59D0, 0.9f);
            outline(c, 7, top + 2, w - 7, bottom - 2, 0xFF6E4A86, 0.45f);

            // Same subtle micro-grid pattern as Persona 2, recolored to match Persona 1.
            paint.setColor(0x229B59D0);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.35f);
            for (float gx = 10; gx < w - 8; gx += 5) {
                c.drawLine(gx, top + 3, gx, bottom - 3, paint);
            }
            for (float gy = top + 4; gy < bottom - 3; gy += 5) {
                c.drawLine(8, gy, w - 8, gy, paint);
            }
            paint.setStyle(Paint.Style.FILL);

            if (selected < 0 || selected >= party.length) {
                drawButterfly(c, w * 0.69f, top + 31f, 15f, 0x30C77BFF);
                text(c, "PARTY", 10, top + 14, 9, WHITE, true, Paint.Align.LEFT);
                text(c, "Tap a card to view status", 10, top + 30, 7.5f, WHITE, false, Paint.Align.LEFT);
                return;
            }

            Unit unit = party[selected];
            float buttonW = 48f, buttonH = 17f;
            float fullX = w - buttonW - 11f, by = top + 19f;

            String heading = unit.name.toUpperCase() + "    LV. " + unit.level;
            text(c, heading, 10, top + 13,
                fit(heading, fullX - 18f, 8.2f), WHITE, true, Paint.Align.LEFT);
            textSpaced(c, "HP  " + unit.hp + " / " + unit.maxHp, 10, top + 28, 7.8f, WHITE, Paint.Align.LEFT);
            float barW = Math.min(w - 20, 142);
            drawPanelBar(c, 10, top + 31, barW, 3.2f, unit.hp, unit.maxHp, HP_COLOR);
            textSpaced(c, "SP  " + unit.sp + " / " + unit.maxSp, 10, top + 44, 7.8f, WHITE, Paint.Align.LEFT);
            drawPanelBar(c, 10, top + 47, barW, 3.2f, unit.sp, unit.maxSp, SP_COLOR);

            drawButterfly(c, w * 0.69f, top + 31f, 15f, 0x30C77BFF);

            fill(c, fullX, by, fullX + buttonW, by + buttonH, 0xFF251333);
            outline(c, fullX, by, fullX + buttonW, by + buttonH, GREEN, 0.8f);
            text(c, "Full portrait", fullX + buttonW / 2f, by + 11.5f, 6.8f, WHITE, true, Paint.Align.CENTER);
        }

        private void drawButterfly(Canvas c, float cx, float cy, float size, int color) {
            int oldColor = paint.getColor();
            Paint.Style oldStyle = paint.getStyle();
            float oldStroke = paint.getStrokeWidth();
            Path wings = new Path();
            wings.moveTo(cx, cy);
            wings.cubicTo(cx - size * 0.15f, cy - size * 0.95f, cx - size * 1.15f, cy - size * 1.05f, cx - size * 0.95f, cy - size * 0.12f);
            wings.cubicTo(cx - size * 0.88f, cy + size * 0.38f, cx - size * 0.28f, cy + size * 0.40f, cx, cy);
            wings.moveTo(cx, cy);
            wings.cubicTo(cx + size * 0.15f, cy - size * 0.95f, cx + size * 1.15f, cy - size * 1.05f, cx + size * 0.95f, cy - size * 0.12f);
            wings.cubicTo(cx + size * 0.88f, cy + size * 0.38f, cx + size * 0.28f, cy + size * 0.40f, cx, cy);
            wings.moveTo(cx, cy + size * 0.05f);
            wings.cubicTo(cx - size * 0.65f, cy + size * 0.20f, cx - size * 0.72f, cy + size * 0.85f, cx - size * 0.12f, cy + size * 0.58f);
            wings.cubicTo(cx - size * 0.05f, cy + size * 0.40f, cx - size * 0.02f, cy + size * 0.20f, cx, cy + size * 0.05f);
            wings.moveTo(cx, cy + size * 0.05f);
            wings.cubicTo(cx + size * 0.65f, cy + size * 0.20f, cx + size * 0.72f, cy + size * 0.85f, cx + size * 0.12f, cy + size * 0.58f);
            wings.cubicTo(cx + size * 0.05f, cy + size * 0.40f, cx + size * 0.02f, cy + size * 0.20f, cx, cy + size * 0.05f);
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(wings, paint);
            paint.setColor(oldColor);
            paint.setStyle(oldStyle);
            paint.setStrokeWidth(oldStroke);
        }

        private void clampPortraitOffsets() {
            if (fullPortraitUnit == null) return;
            Bitmap bitmap = getPortrait(fullPortraitUnit);
            if (bitmap == null) return;
            float availableW = Math.max(1f, getWidth() / viewScale - 12f);
            float availableH = 210f;
            float fit = Math.min(availableW / bitmap.getWidth(), availableH / bitmap.getHeight());
            float halfW = bitmap.getWidth() * fit * portraitZoom / 2f;
            float halfH = bitmap.getHeight() * fit * portraitZoom / 2f;
            float maxX = Math.max(0f, halfW - availableW / 2f);
            float maxY = Math.max(0f, halfH - availableH / 2f);
            portraitOffsetX = Math.max(-maxX, Math.min(maxX, portraitOffsetX));
            portraitOffsetY = Math.max(-maxY, Math.min(maxY, portraitOffsetY));
        }

        private void drawPanelBar(Canvas c, float x, float y, float width, float height, int value, int max, int color) {
            fill(c, x, y, x + width, y + height, 0xFF777D7E);
            float f = max <= 0 ? 0 : Math.max(0, Math.min(1, value / (float)max));
            if (f > 0) fill(c, x, y, x + width * f, y + height, color);
        }


        private void textSpaced(Canvas c, String value, float x, float y, float size, int color, Paint.Align align) {
            // Keep the Persona 4 Golden typeface but draw glyphs separately to prevent
            // overlapping number pairs in the bottom status panel.
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(color);
            paint.setAlpha(255);
            paint.setTypeface(font);
            paint.setTextSize(size);
            float total = 0;
            for (int i = 0; i < value.length(); i++) total += paint.measureText(value.substring(i, i + 1)) + 0.75f;
            float cursor = x;
            if (align == Paint.Align.RIGHT) cursor -= total;
            else if (align == Paint.Align.CENTER) cursor -= total / 2f;
            for (int i = 0; i < value.length(); i++) {
                String ch = value.substring(i, i + 1);
                c.drawText(ch, cursor, y, paint);
                cursor += paint.measureText(ch) + 0.75f;
            }
        }

        private void outlineText(Canvas c, String value, float x, float y, float size, int color, boolean bold, Paint.Align align) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setShader(null);
            paint.setStrokeWidth(1.25f);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xFF101314);
            paint.setTypeface(bold ? boldFont : font);
            paint.setTextSize(size);
            paint.setTextAlign(align);
            c.drawText(value, x, y, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            c.drawText(value, x, y, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private float fit(String value, float maxWidth, float size) {
            paint.setTypeface(font);
            paint.setTextSize(size);
            float measured = paint.measureText(value);
            return measured <= maxWidth ? size : Math.max(5, size * maxWidth / Math.max(1, measured));
        }

        private String initials(String name) {
            return name.length() <= 2 ? name.toUpperCase() : name.substring(0, 2).toUpperCase();
        }

        private void fill(Canvas c, float l, float t, float r, float b, int color) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setAlpha((color >>> 24) & 0xFF);
            c.drawRect(l, t, r, b, paint);
            paint.setAlpha(255);
        }

        private void stroke(Canvas c, float l, float t, float r, float b, int color, float width) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            c.drawRect(l, t, r, b, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void outline(Canvas c, float l, float t, float r, float b, int color, float width) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            rect.set(l, t, r, b);
            c.drawRect(rect, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void textPlain(Canvas c, String value, float x, float y, float size, int color, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(color);
            paint.setAlpha(255);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(size);
            paint.setTextAlign(align);
            c.drawText(value, x, y, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void text(Canvas c, String value, float x, float y, float size, int color, boolean bold, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(color);
            paint.setAlpha(255);
            paint.setTypeface(bold ? boldFont : font);
            paint.setTextSize(size);
            paint.setTextAlign(align);
            c.drawText(value, x, y, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float scale = getHeight() / 240f;
            if (scale <= 0) return true;
            float x = event.getX() / scale;
            float y = event.getY() / scale;
            float w = getWidth() / scale;

            if (showFullPortrait) {
                scaleDetector.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        portraitPointerId = event.getPointerId(0);
                        portraitLastX = x; portraitLastY = y;
                        portraitGestureUsed = false; dragging = true; moved = false;
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        portraitGestureUsed = true; moved = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!scaleDetector.isInProgress() && event.getPointerCount() == 1 &&
                            dragging && portraitZoom > 1f && portraitPointerId != MotionEvent.INVALID_POINTER_ID) {
                            int pi = event.findPointerIndex(portraitPointerId);
                            if (pi >= 0) {
                                float px = event.getX(pi) / scale, py = event.getY(pi) / scale;
                                float dx = px - portraitLastX, dy = py - portraitLastY;
                                portraitOffsetX += dx; portraitOffsetY += dy;
                                clampPortraitOffsets();
                                if (Math.abs(dx) + Math.abs(dy) > 1f) {
                                    moved = true; portraitGestureUsed = true;
                                }
                                portraitLastX = px; portraitLastY = py;
                                invalidate();
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_POINTER_UP:
                        portraitGestureUsed = true; moved = true;
                        return true;
                    case MotionEvent.ACTION_UP:
                        dragging = false;
                        if (!moved && !portraitGestureUsed) {
                            showFullPortrait = false; fullPortraitUnit = null;
                            portraitZoom = 1f; portraitOffsetX = portraitOffsetY = 0f;
                        }
                        performClick(); invalidate(); return true;
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false; return true;
                }
                return true;
            }
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = x;
                    downY = y;
                    dragging = true;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return true;
                    float dx = x - lastX;
                    if (Math.abs(x - downX) > 4) moved = true;
                    if (moved && downY < PANEL_TOP - 2) {
                        float total = party.length * CARD_W + Math.max(0, party.length - 1) * GAP;
                        float maxScroll = Math.max(0, total - (w - MARGIN * 2));
                        scrollX = Math.max(0, Math.min(maxScroll, scrollX - dx));
                        invalidate();
                    }
                    lastX = x;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) return true;
                    dragging = false;
                    if (moved) return true;
                    if (showFullPortrait) {
                        showFullPortrait = false;
                        fullPortraitUnit = null;
                        invalidate();
                        return true;
                    }
                    if (y >= CARD_TOP && y <= CARD_TOP + CARD_H && x >= MARGIN && x <= w - MARGIN) {
                        int index = (int)((x + scrollX - MARGIN) / (CARD_W + GAP));
                        float within = (x + scrollX - MARGIN) % (CARD_W + GAP);
                        if (index >= 0 && index < party.length && within <= CARD_W) {
                            if (selected == index) {
                                if (detailMode) detailMode = false;
                                else selected = -1;
                            } else {
                                selected = index;
                                detailMode = false;
                            }
                            invalidate();
                            return true;
                        }
                    }
                    if (y >= PANEL_TOP && y <= PANEL_BOTTOM) {
                        if (selected >= 0) {
                            float buttonW = 48f;
                            float buttonY = PANEL_TOP + 19f;
                            float fullLeft = w - buttonW - 11f;
                            if (y >= buttonY && y <= buttonY + 17f && x >= fullLeft && x <= fullLeft + buttonW) {
                                fullPortraitUnit = party[selected];
                                showFullPortrait = true;
                                portraitZoom = 1f;
                                portraitOffsetX = portraitOffsetY = 0f;
                            } else {
                                detailMode = !detailMode;
                            }
                        }
                        invalidate();
                        return true;
                    }
                    if (selected >= 0) {
                        selected = -1;
                        detailMode = false;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
            }
            return true;
        }
    }
}
