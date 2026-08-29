#include "debug_config.h"
#ifdef DEBUG_WARP

#include "patches.h"
#include "PR/os_cont.h"
#define ARRAY_COUNT(arr) (sizeof(arr) / sizeof(arr[0]))

typedef struct TextOverlay {
    /* 0x000 */ char unk_00[0x15F];
    /* 0x004 */ //u16
    /* 0x006 */ //u16
    /* 0x008 */ //u8
    /* 0x009 */ //u8
    /* 0x054 */ //layer ID?
    /* 0x15F */ char opacity;
    /* 0x160 */ char unk_160[0x0A];
    /* 0x16A */ unsigned char red;
    /* 0x16B */ unsigned char green;
    /* 0x16C */ unsigned char blue;
    /* 0x16D */ char unk_16D[0x0B];
    /* 0x178 */ char* string;
} TextOverlay;

//data
extern TextOverlay* gLastSpawnedActor; //0x807FBB44
extern short g_AmmoCount; //0x807FCC40
extern short newly_pressed_input; //0x807ECD48
int* textObjectInstancesPrevious[24]; //0x807FFF00
TextOverlay* textObjectInstancesCurrent[24]; //0x807FFF80
TextOverlay* textObjectInstancesCurrentSelectedObj; //0x807FFF80
int* graphicalOverlaySpace[32][3]; //0x807FFD80
char warpsSubMenu; //0x807FFD6E
char currentMenu; //0x807FFD6F
char inputTimer; //0x807FFD70
char mainMenuBoolean; //0x807FFD71
char mainCursorIndex = 0; //0x807FFD72
char menuFlag = 0; //0x807FFD73
int* printStartAddr; //custom
unsigned char headerStyle; //custom
unsigned char tableStyle; //custom
unsigned char currentFormat; //custom
unsigned short cheatsBitField;
extern void func_global_asm_806785D4(TextOverlay* actor); //0x806785D4
void initWarpMenu(void);
void updateWarpMenu(void);
extern void func_global_asm_805FF378(int area, int entrance); //args are a guess


typedef struct WarpMenu {
    char* mapName;
    s16 mapID;
} WarpMenu;

WarpMenu warps[] = {
    {"JAPES",   0x070F},
    {"AZTEC",   0x2600},
    {"FACTORY", 0x1A00},
    {"GALLEON", 0x1E00},
    {"FOREST",  0x301B},
    {"CAVES",   0x4800},
    {"CASTLE",  0x5700},
    {"HELM",    0x1100},
};

void func_global_asm_8069D0F8(int style, int x, int y, char* string, int timer1, int timer2, unsigned char effect, unsigned char speed);
extern OSContPad D_global_asm_807ECD58;

void setActorOpacity(TextOverlay* textOverlay, u8 a) {
    textOverlay->opacity = a;
}

void setOverlayColor(TextOverlay* textOverlay, u8 r, u8 g, u8 b) {
    textOverlay->red = r;
    textOverlay->green = g;
    textOverlay->blue = b;
}
 
void createWarpOptionTextObjs(void) {
    TextOverlay* textOverlay;
    int x = 10;
    int y = 40;
    int style = 10;
    
    for (int i = 0; i < ARRAY_COUNT(warps); i++) {
        func_global_asm_8069D0F8(style, x, y, (warps[i].mapName), 0, 0, 2, 0);
        textOverlay = gLastSpawnedActor;
        textObjectInstancesCurrent[i] = textOverlay;
        textObjectInstancesCurrent[i]->string = warps[i].mapName;
        setActorOpacity(textOverlay, 0xff);
        y += 15;
    }

}

void destroyWarpOptionTextObjs(void) {
    for (int i = 0; i < ARRAY_COUNT(warps); i++) {
        func_global_asm_806785D4(textObjectInstancesCurrent[i]);
    }
}

void handleMenuInputs(void) {
    if (newly_pressed_input & U_JPAD) {
        if (--mainCursorIndex < 0) {
            mainCursorIndex = ARRAY_COUNT(warps) - 1;
            setOverlayColor(textObjectInstancesCurrent[0], 0xFF, 0xFF, 0xFF); //white
            setOverlayColor(textObjectInstancesCurrent[mainCursorIndex], 0, 0xFF, 00); //green
        } else {
            setOverlayColor(textObjectInstancesCurrent[mainCursorIndex+1], 0xFF, 0xFF, 0xFF); //white
            setOverlayColor(textObjectInstancesCurrent[mainCursorIndex], 0, 0xFF, 00); //green
        }
    } else if(newly_pressed_input & D_JPAD) {
        mainCursorIndex++;
        if (mainCursorIndex >= ARRAY_COUNT(warps)) {
            mainCursorIndex = 0;
            setOverlayColor(textObjectInstancesCurrent[ARRAY_COUNT(warps) - 1], 0xFF, 0xFF, 0xFF); //white
            setOverlayColor(textObjectInstancesCurrent[0], 0, 0xFF, 00); //green
        } else {
            setOverlayColor(textObjectInstancesCurrent[mainCursorIndex - 1], 0xFF, 0xFF, 0xFF); //white
            setOverlayColor(textObjectInstancesCurrent[mainCursorIndex], 0, 0xFF, 00); //green
        }

    }

    if (newly_pressed_input & Z_TRIG) {
        short room = warps[mainCursorIndex].mapID >> 8; //shift upper byte of s16 to lower byte
        short entrance = warps[mainCursorIndex].mapID & 0xFF;
        menuFlag = 0;
        destroyWarpOptionTextObjs();
        func_global_asm_805FF378(room, entrance); //warp
    }
}

void menuMain(void) {
    TextOverlay* textOverlay;
    int x = 10;
    int y = 40;
    int style = 10;
    int prev = menuFlag;

    if ((newly_pressed_input & U_JPAD) && (D_global_asm_807ECD58.button & R_TRIG)) {
        menuFlag ^= 1;
        if (menuFlag == 0) {
            destroyWarpOptionTextObjs();
        } else if (menuFlag == 1) {
            createWarpOptionTextObjs();
            setOverlayColor(textObjectInstancesCurrent[mainCursorIndex], 0, 0xFF, 00); //green
        }
    }

    //prevents the dpad up press of opening the menu from being input as scrolling
    if (menuFlag == 1 && prev == 1) {
        handleMenuInputs();
    }
}

#endif //DEBUG_WARP