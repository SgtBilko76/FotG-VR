#ifndef OPENXR_QUEST_SETUP_H
#define OPENXR_QUEST_SETUP_H

/* VR controller setup panel for the Quest build: opened in flight by a long press of the
 * left Menu button, shown on the same quad as the virtual keyboard
 * (VR_KB_WIDTH_PX x VR_KB_HEIGHT_PX). Two buttons pick the stick layout. Drawn by a CPU
 * rasterizer into an RGBA image (premultiplied alpha); input and the quad pose live in
 * openxr_quest.cpp. */

#include <stdint.h>

enum {
	VR_STICKS_JET     = 0, /* right stick flies, left stick = throttle lever + roll */
	VR_STICKS_CLASSIC = 1, /* left stick flies, right stick = throttle lever + roll */
	VR_SETUP_NONE     = -1,
};

/* Button (VR_STICKS_JET / VR_STICKS_CLASSIC) at normalised image coordinates (0..1, v down),
 * or VR_SETUP_NONE. */
int VrSetup_ButtonAt(float u, float v);

/* Draws the panel. `active` = current layout, hover0/hover1 = button under each controller
 * ray (or VR_SETUP_NONE). */
void VrSetup_Render(uint8_t* rgba, int active, int hover0, int hover1);

/* Comms panel (same quad): the squad message menu while it is open. Items 0..count-1 of the shown
 * page in two columns (1-5 left, 6-10 right), then a bottom row with page buttons and Close. */
enum {
	VR_COMMS_MAX   = 10,
	VR_COMMS_PREV  = 100,
	VR_COMMS_NEXT  = 101,
	VR_COMMS_CLOSE = 102,
};

/* item 0..count-1, VR_COMMS_PREV/NEXT/CLOSE, or VR_SETUP_NONE */
int VrComms_ButtonAt(float u, float v, int count, int has_prev, int has_next);

/* active[i]: 1 = can be picked, 0 = greyed out, -1 = hidden */
void VrComms_Render(uint8_t* rgba, const char* title, const char* const* labels, const int* active, int count,
	int has_prev, int has_next, int hover0, int hover1);

#endif
