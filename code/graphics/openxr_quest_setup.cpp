#include "graphics/openxr_quest_setup.h"

#include "graphics/openxr_quest_keyboard.h"
#include "graphics/openxr_quest_keyboard_font.h"

#include <stdio.h>
#include <string.h>

/* Rasterizer (premultiplied RGBA8), same look as the keyboard and the radial menus */

namespace {

const int W = VR_KB_WIDTH_PX, H = VR_KB_HEIGHT_PX;

struct SRgba {
	uint8_t r, g, b, a;
};

void s_blend(uint8_t* p, SRgba c, int coverage)
{
	const int a   = c.a * coverage / 255;
	const int inv = 255 - a;
	p[0]          = (uint8_t)((c.r * a + p[0] * inv) / 255);
	p[1]          = (uint8_t)((c.g * a + p[1] * inv) / 255);
	p[2]          = (uint8_t)((c.b * a + p[2] * inv) / 255);
	p[3]          = (uint8_t)(a + p[3] * inv / 255);
}

void s_px(uint8_t* img, int x, int y, SRgba c, int coverage)
{
	if (x < 0 || y < 0 || x >= W || y >= H)
		return;
	s_blend(img + ((size_t)y * W + x) * 4, c, coverage);
}

void s_round_rect(uint8_t* img, int x0, int y0, int x1, int y1, int r, SRgba c)
{
	for (int y = y0; y < y1; ++y) {
		for (int x = x0; x < x1; ++x) {
			int dx = 0, dy = 0;
			if (x < x0 + r)
				dx = x0 + r - x;
			else if (x >= x1 - r)
				dx = x - (x1 - r - 1);
			if (y < y0 + r)
				dy = y0 + r - y;
			else if (y >= y1 - r)
				dy = y - (y1 - r - 1);
			if (dx * dx + dy * dy <= r * r)
				s_px(img, x, y, c, 255);
		}
	}
}

int s_text_width(const char* text)
{
	int width = 0;
	for (const char* p = text; *p; ++p) {
		const unsigned char ch = (unsigned char)*p;
		if (ch >= VR_FONT_FIRST && ch <= VR_FONT_LAST)
			width += vr_font_glyphs[ch - VR_FONT_FIRST].advance;
	}
	return width;
}

void s_text(uint8_t* img, const char* text, int cx, int cy, SRgba c)
{
	int pen_x    = cx - s_text_width(text) / 2;
	int baseline = cy + VR_FONT_ASCENT / 2 - 2;
	for (const char* p = text; *p; ++p) {
		const unsigned char ch = (unsigned char)*p;
		if (ch < VR_FONT_FIRST || ch > VR_FONT_LAST)
			continue;
		const VrGlyph* g = &vr_font_glyphs[ch - VR_FONT_FIRST];
		for (int gy = 0; gy < g->h; ++gy)
			for (int gx = 0; gx < g->w; ++gx) {
				const int coverage = vr_font_bits[g->offset + gy * g->w + gx];
				if (coverage)
					s_px(img, pen_x + g->xoff + gx, baseline + g->yoff + gy, c, coverage);
			}
		pen_x += g->advance;
	}
}

// button rectangles (pixels): Jet left, Classic right
const int BTN_Y0 = 100, BTN_Y1 = 404;
const int BTN_X[2][2] = { { 40, 620 }, { 660, 1240 } };

struct ButtonText {
	const char* name;
	const char* lines[2];
};
const ButtonText BUTTONS[2] = {
	{ "Jet", { "Right stick: fly", "Left stick: throttle, roll" } },
	{ "Classic", { "Left stick: fly", "Right stick: throttle, roll" } },
};

} // namespace

int VrSetup_ButtonAt(float u, float v)
{
	const int x = (int)(u * W), y = (int)(v * H);
	if (y < BTN_Y0 || y >= BTN_Y1)
		return VR_SETUP_NONE;
	for (int b = 0; b < 2; b++)
		if (x >= BTN_X[b][0] && x < BTN_X[b][1])
			return b;
	return VR_SETUP_NONE;
}

namespace {

// comms panel geometry (pixels)
const int CM_TOP = 72, CM_ROW_H = 64, CM_GAP = 6;
const int CM_COL_X[2][2] = { { 24, 632 }, { 648, 1256 } };
const int CM_BTN_Y0 = 404, CM_BTN_Y1 = 480;
const int CM_BTN_X[3][2] = { { 24, 424 }, { 440, 840 }, { 856, 1256 } }; // prev, next, close

void cm_item_rect(int i, int& x0, int& y0, int& x1, int& y1)
{
	const int col = i / 5, row = i % 5;
	x0 = CM_COL_X[col][0];
	x1 = CM_COL_X[col][1];
	y0 = CM_TOP + row * CM_ROW_H;
	y1 = y0 + CM_ROW_H - CM_GAP;
}

// text cut to max_w pixels (with "..")
void s_fit(const char* in, char* out, size_t outsz, int max_w)
{
	if (out != in) {
		strncpy(out, in, outsz - 1);
		out[outsz - 1] = '\0';
	}
	size_t n = strlen(out);
	if (s_text_width(out) <= max_w)
		return;
	while (n > 0) {
		out[--n] = '\0';
		char tmp[256];
		snprintf(tmp, sizeof(tmp), "%s..", out);
		if (s_text_width(tmp) <= max_w) {
			strncpy(out, tmp, outsz - 1);
			out[outsz - 1] = '\0';
			return;
		}
	}
}

// left-aligned text, vertically centred on cy
void s_text_left(uint8_t* img, const char* text, int x, int cy, SRgba c)
{
	s_text(img, text, x + s_text_width(text) / 2, cy, c);
}

} // namespace

int VrComms_ButtonAt(float u, float v, int count, int has_prev, int has_next)
{
	const int x = (int)(u * W), y = (int)(v * H);
	for (int i = 0; i < count && i < VR_COMMS_MAX; i++) {
		int x0, y0, x1, y1;
		cm_item_rect(i, x0, y0, x1, y1);
		if (x >= x0 && x < x1 && y >= y0 && y < y1)
			return i;
	}
	if (y >= CM_BTN_Y0 && y < CM_BTN_Y1) {
		if (has_prev && x >= CM_BTN_X[0][0] && x < CM_BTN_X[0][1])
			return VR_COMMS_PREV;
		if (has_next && x >= CM_BTN_X[1][0] && x < CM_BTN_X[1][1])
			return VR_COMMS_NEXT;
		if (x >= CM_BTN_X[2][0] && x < CM_BTN_X[2][1])
			return VR_COMMS_CLOSE;
	}
	return VR_SETUP_NONE;
}

void VrComms_Render(uint8_t* rgba, const char* title, const char* const* labels, const int* active, int count,
	int has_prev, int has_next, int hover0, int hover1)
{
	static const SRgba panel   = { 12, 16, 24, 225 };
	static const SRgba item    = { 40, 52, 72, 245 };
	static const SRgba itemOff = { 28, 34, 44, 235 };
	static const SRgba itemHov = { 255, 200, 40, 255 };
	static const SRgba text    = { 235, 240, 250, 255 };
	static const SRgba dim     = { 120, 128, 140, 255 };
	static const SRgba textSel = { 20, 20, 25, 255 };

	memset(rgba, 0, (size_t)W * H * 4);
	s_round_rect(rgba, 0, 0, W, H, 28, panel);
	char buf[256];
	snprintf(buf, sizeof(buf), "Comms: %s", title && title[0] ? title : "Message What");
	s_fit(buf, buf, sizeof(buf), W - 60);
	s_text(rgba, buf, W / 2, 38, text);

	int shown = 0;
	for (int i = 0; i < count && i < VR_COMMS_MAX; i++) {
		if (active[i] < 0)
			continue;
		int x0, y0, x1, y1;
		cm_item_rect(i, x0, y0, x1, y1);
		const bool hov = active[i] > 0 && (i == hover0 || i == hover1);
		s_round_rect(rgba, x0, y0, x1, y1, 16, hov ? itemHov : (active[i] > 0 ? item : itemOff));
		char label[256];
		snprintf(label, sizeof(label), "%d. %s", (i + 1) % VR_COMMS_MAX, labels[i]);
		s_fit(label, label, sizeof(label), x1 - x0 - 36);
		s_text_left(rgba, label, x0 + 18, (y0 + y1) / 2, hov ? textSel : (active[i] > 0 ? text : dim));
		shown++;
	}
	if (shown == 0)
		s_text(rgba, "Nothing to order right now", W / 2, CM_TOP + 2 * CM_ROW_H, dim);

	const char* names[3] = { "< Previous", "More >", "Close" };
	const int ids[3] = { VR_COMMS_PREV, VR_COMMS_NEXT, VR_COMMS_CLOSE };
	const int on[3] = { has_prev, has_next, 1 };
	for (int b = 0; b < 3; b++) {
		if (!on[b])
			continue;
		const bool hov = ids[b] == hover0 || ids[b] == hover1;
		s_round_rect(rgba, CM_BTN_X[b][0], CM_BTN_Y0, CM_BTN_X[b][1], CM_BTN_Y1, 16, hov ? itemHov : item);
		s_text(rgba, names[b], (CM_BTN_X[b][0] + CM_BTN_X[b][1]) / 2, (CM_BTN_Y0 + CM_BTN_Y1) / 2, hov ? textSel : text);
	}
}

void VrSetup_Render(uint8_t* rgba, int active, int hover0, int hover1)
{
	static const SRgba panel   = { 12, 16, 24, 225 };
	static const SRgba item    = { 40, 52, 72, 245 };
	static const SRgba itemHov = { 64, 82, 112, 250 };
	static const SRgba itemSel = { 255, 200, 40, 255 };
	static const SRgba text    = { 235, 240, 250, 255 };
	static const SRgba dim     = { 170, 180, 196, 255 };
	static const SRgba textSel = { 20, 20, 25, 255 };
	static const SRgba frame   = { 235, 240, 250, 255 };

	memset(rgba, 0, (size_t)W * H * 4);
	s_round_rect(rgba, 0, 0, W, H, 28, panel);
	s_text(rgba, "VR controller setup: sticks", W / 2, 52, text);

	for (int b = 0; b < 2; b++) {
		const int x0 = BTN_X[b][0], x1 = BTN_X[b][1];
		const bool sel = b == active, hov = b == hover0 || b == hover1;
		if (hov) // white frame under the laser
			s_round_rect(rgba, x0 - 6, BTN_Y0 - 6, x1 + 6, BTN_Y1 + 6, 26, frame);
		s_round_rect(rgba, x0, BTN_Y0, x1, BTN_Y1, 22, sel ? itemSel : (hov ? itemHov : item));
		const int cx = (x0 + x1) / 2;
		const SRgba& t = sel ? textSel : text;
		s_text(rgba, BUTTONS[b].name, cx, BTN_Y0 + 62, t);
		s_text(rgba, BUTTONS[b].lines[0], cx, BTN_Y0 + 152, t);
		s_text(rgba, BUTTONS[b].lines[1], cx, BTN_Y0 + 212, t);
		if (sel)
			s_text(rgba, "(active)", cx, BTN_Y0 + 266, t);
	}
	s_text(rgba, "Point + trigger: saved at once.  Menu or B: close", W / 2, 448, dim);
}
