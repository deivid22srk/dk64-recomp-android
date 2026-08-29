#define MATH_HALF_PI_D 1.5707963705062866
#define gScissorUpLX D_global_asm_80744498
#define gScissorUpLY D_global_asm_8074449C
#define gScissorLowerRightX D_global_asm_807444A0
#define gScissorLowerRightY D_global_asm_807444A4

extern u8 D_global_asm_807444FC;
extern s16 D_global_asm_80744490;
extern s16 D_global_asm_80744494;
extern s16 D_global_asm_80744498;
extern s16 D_global_asm_8074449C;
extern s16 D_global_asm_807444A0;
extern s16 D_global_asm_807444A4;
extern u8 D_global_asm_807F6009;
extern u32 D_global_asm_807F600C;
extern u32 global_properties_bitfield;
extern Actor *gPlayerPointer;
extern Mtx D_2000000;
extern Mtx D_20000C0;
extern Mtx D_2000180;
extern Mtx D_2000200;
extern void func_global_asm_8065C334(f32 arg0, f32 arg1, f32 arg2, s16 arg3, u8 *arg4, u8 *arg5, u8 *arg6, s16 arg7);
extern s32 func_global_asm_806522CC(s16 arg0, s16 arg1, s16 arg2);
extern u8 func_global_asm_80651B64(s16 arg0);
extern Gfx *func_global_asm_805FD030(Gfx *dl);
extern u8 cc_player_index;

extern u8 D_global_asm_807FDB1D; // This is being used to determine the sprite alignment

typedef struct {
    s32 unk0; // screen x
    s32 unk4; // screen y
    Mtx unk8[2];
} Struct806F9D8C_arg14;

typedef struct {
    s32 unk0;
    f32 unk4;
    f32 unk8;
    s32 unkC;
    s16 unk10;
    s16 unk12;
    void *unk14;
} Struct806FA504_arg1;

typedef struct HUDDisplay {
	/* 0x000 */ u16* actual_count_pointer;
	/* 0x004 */	u16 hud_count;
	/* 0x006 */	u8 freeze_timer;
	/* 0x007 */	u8 counter_timer;
	/* 0x008 */	s32 screen_x;
	/* 0x00C */	s32 screen_y;
	/* 0x010 */ f32 unk_10;
    /* 0x014 */ f32 unk_14;
    /* 0x018 */ f32 unk_18;
    /* 0x01C */ u8 unk_1c;
    /* 0x01D */ u8 unk_1d;
    /* 0x01E */ u8 unk_1e;
    /* 0x01F */ u8 unk_1f;
	/* 0x020 */ u32 hud_state; // 0 = invisible, 1 = appearing, 2 = visible, 3 = disappearing
	/* 0x024 */ s32 unk_24;
	/* 0x028 */	void* counter_pointer;
	/* 0x02C */ u8 unk_2c; // Infinites?
    /* 0x02D */ u8 unk_2d; // Infinites?
    /* 0x02E */ u8 unk_2e;
    /* 0x02F */ u8 unk_2f;
} HUDDisplay;

typedef struct {
    // TODO: Union with friendly field names?
    // TODO: Enum with indexes?
    // 0 = Coloured Banana
    // 1 = Banana Coin
    // 2 = ???
    // 3 = ???
    // 4 = ???
    // 5 = Crystal Coconut
    // 6 = ???
    // 7 = ???
    // 8 = GB Count (Character)
    // 9 = ???
    // 10 = Banana Medal
    // 11 = ???
    // 12 = Blueprint
    // 13 = Coloured Banana?
    // 14 = Banana Coin?
    HUDDisplay hud_item[15];
} PlayerHUD;

typedef struct {
    u8 unk0;
    u8 unk1;
    u8 unk2;
    u8 unk3;
    union {
        struct {
            s16 unk4;
            s16 unk6;
            s16 unk8;
            s16 unkA;
        };
        s16 unk4_arr[4];
    };
} Struct80750948;
extern u8 D_global_asm_807FD7E4;
extern f32 func_global_asm_80612794(s16 arg0);
extern int _sprintf(char *s, const char *fmt, ...);
extern f32 func_global_asm_80612E40(f32 arg0);
extern s32 getCenterOfString(s16 renderStyle, u8 *string);
extern Struct80750948 *func_global_asm_806C7C94(u8 arg0);
extern Mtx D_2000080;
extern Gfx **D_1000118;
extern u8 D_global_asm_807444FC;
extern Gfx *printStyledText(Gfx *dl, s16 style, s16 x, s16 y, u8 *string, u32 extraBitfield);
extern PlayerHUD *D_global_asm_80754280;
extern s16 D_global_asm_80744490;

typedef struct {
    f32 unk0;
    f32 unk4;
    f32 unk8;
    f32 unkC;
    f32 unk10;
    f32 unk14;
    f32 unk18;
    f32 unk1C;
    f32 unk20;
    f32 unk24;
} Struct80717D84_80030894;

extern void func_menu_80030C14(s32, void*, void*);

extern void func_global_asm_80714950(s32 arg0);
extern void func_global_asm_8071495C(void);
extern void func_global_asm_8071498C(void *arg0);
extern void func_global_asm_80714998(u8 arg0);
extern void func_global_asm_807149FC(s32 arg0);
extern void func_global_asm_80714A28(u16 arg0);
extern Struct80717D84 *drawSpriteAtPosition(void *sprite, f32 scale, f32 x, f32 y, f32 z);
extern void *_malloc(s32);
extern s8 D_menu_80033F38;

typedef struct {
    s16 unk0;
    s16 unk2;
    s8 unk4;
    s8 unk5;
    s8 unk6;
    s8 unk7;
    Struct80717D84 *unk8;
} Struct806F9744_arg0_unk14;

typedef struct {
    s32 unk0;
    s32 unk4;
    s32 unk8;
    s32 unkC;
    s32 unk10;
    Struct806F9744_arg0_unk14 *unk14;
} Struct806F9744_arg0;
extern void func_global_asm_80714944(s32 arg0);
extern void changeActorColor(u8 red, u8 green, u8 blue, u8 alpha);
extern void *func_global_asm_806FACE8(u32 arg0);
extern void func_global_asm_806F94AC(Struct80717D84 *arg0, s32 arg1);
extern void func_global_asm_8071BE04(Struct80717D84 *arg0, s32 arg1);

extern void *func_global_asm_8068C12C(u16 tex);
extern f32 func_global_asm_80612D10(f32 arg0);
extern f32 func_global_asm_80612D1C(f32 arg0);

extern u8 *getTextString(u8 fileIndex, s32 stringIndex, s32 arg2);
extern Mtx D_global_asm_807FDAC0;
extern u32 object_timer;

typedef struct {
    s32 id;
    u8 images_per_frame_horizontal;
    u8 images_per_frame_vertical;
    u8 unk6;
    u8 codec;
    u8 unk8;
    u8 unk9;
    u8 unkA;
    u8 unkB;
    u8 unkC;
    u8 table;
    s16 width;
    s16 height;
    s16 image_count;
    s16 images[1]; // TODO: How many elements? m2c doesn't support VLAs
} SpriteData;

typedef struct global_asm_struct_71 GlobalASMStruct71;

struct global_asm_struct_71 {
    s32 unk0;
    s32 unk4;
    s32 unk8; // Used
    s32 unkC;
    s16 unk10;
    s16 unk12;
    GlobalASMStruct71 *unk14; // Used, prev?
    GlobalASMStruct71 *unk18; // Next?
};

typedef struct {
    u8 unk0[0x340 - 0x0];
    f32 unk340;
    f32 unk344;
    u8 unk348[0x35E - 0x348];
    s16 unk35E;
    f32 unk360;
    f32 unk364;
} Struct806F9AF0_arg0;

extern SpriteData *D_global_asm_80750518[];
extern f32 D_global_asm_807FD7A0[];
extern void func_global_asm_806F9AF0(GlobalASMStruct71 *arg0, s8 *arg1);
extern void func_global_asm_806F966C(GlobalASMStruct71 **arg0);
extern void func_global_asm_806F96CC(GlobalASMStruct71 *arg0, u32 arg1);

typedef struct {
    s32 unk0;
    void *unk4[1];
    s32 unk8;
    s32 unkC;
    s32 unk10;
    s32 unk14;
    s8 unk18[1];
} Struct8002733C;
void func_global_asm_80715908(Struct80717D84 *arg0);
void func_global_asm_8071A038(Struct80717D84 *arg0, s32 arg1);
extern SpriteData D_global_asm_8071FFD4; 
s16 playSound(s16 arg0, s32 arg1, f32 arg2, f32 arg3, u8 arg4, u8 arg5);

typedef struct {
    OSTime unk0;
    u32 unk8;
    s32 unkC;
    u8 unk10;
} AAD_global_asm_806A2A10;
typedef struct Struct80754AD0 Struct80754AD0;

typedef struct {
    f32 unk0;
    f32 unk4;
    f32 unk8;
    f32 unkC;
    f32 unk10;
    f32 unk14;
    f32 unk18;
    f32 unk1C;
    f32 unk20;
    f32 unk24;
    f32 unk28;
    f32 unk2C;
    f32 unk30;
    f32 unk34;
} Struct806FD9FC;

struct Struct80754AD0 {
    Struct80754AD0 *next;
    u8 *unk4;
    Struct806FD9FC *unk8;
    u8 unkC;
    u8 unkD;
    u8 padE[0x10 - 0x0E];
    f32 unk10;
    f32 unk14;
    f32 unk18;
    s16 unk1C;
};

extern SpriteData D_global_asm_8071FC58;
extern u8 func_global_asm_805FCA64(void);
extern Gfx *func_global_asm_8070068C(Gfx *dl);
extern Struct80754AD0 *func_global_asm_806FD9B4(s16 arg0);
extern Gfx *func_global_asm_806FE078(Gfx *dl, s16 arg1, s32 arg2, f32 arg3, f32 arg4, f32 arg5, f32 arg6);
extern Mtx D_2000100;

typedef struct {
    u8 unk0;
    u8 unk1;
    s16 unk2;
    s16 unk4;
    u8 unk6;
    u8 unk7;
    u8 unk8;
    u8 unk9;
} A178_80024000;

typedef struct {
    u8 unk0[0x14 - 0x0];
    s16 unk14;
    s16 unk16;
    u8 unk18;
    u8 unk19;
} AAD_bonus_800252A0;

typedef struct KremlingKoshAAD {
    void* sprite[5];
    u8 unk14[0x1E - 0x14];
    s16 x;
    s16 y;
    u8 unk22;
    u8 timer;
    u8 unk24;
    u8 unk25;
    u8 unk26;
} KremlingKoshAAD;

typedef struct KremlingKoshInit {
    Actor* slots[8];
    s16 hit_requirement;
    s16 hit_requirement_hud;
    u8 unk24;
    u8 unk25;
    u8 unk26;
    u8 no_spawn_percent;
    u8 green_chance;
    u8 time_limit;
    u8 unk2A[2];
    f32 unk2C;
} KremlingKoshInit;

extern Gfx *func_bonus_80026690(Gfx *dl, Actor *arg1);
extern u8 func_global_asm_806FD894(s16 arg0);

Gfx* func_global_asm_8068DC54(Gfx* dl, s16 arg1, s16 arg2, s16* arg3, s16 arg4, u8* arg5);

typedef struct KrazyKKAAD {
    u8 pad0[0x25];
    u8 unk25;
    u8 unk26;
    u8 unk27;
    s16 unk28;
    s16 unk2A;
} KrazyKKAAD;

typedef struct KrazyKKAAD178 {
    u8 pad0[0x3];
    u8 unk3;
    u8 pad4[0x6 - 0x4];
    u8 unk6;
    u8 unk7;
    s16 unk8;
    s16 unkA;
    u8 padC[0x11 - 0xC];
    u8 unk11;
    u8 unk12;
    u8 unk13;
    s16 unk14;
    s16 unk16;
} KrazyKKAAD178;

typedef struct {
    u8 unk0[0x23];
    u8 unk23;
    u8 unk24;
    u8 unk25;
    s16 unk26;
    s16 unk28;
} AAD_8002CC08;

extern Gfx *displayImage(Gfx *dl, u16 textureIndex, s32 arg3, u32 codec, s32 width, s32 height, s16 x, s16 y, f32 xScale, f32 yScale, s32 arg11, f32 arg12);
extern s32 func_global_asm_80626F8C(f32 arg0, f32 arg1, f32 arg2, f32 *arg3, f32 *arg4, s32 arg5, f32 arg6, s32 arg7);
extern f32 D_bonus_8002DEB4;

typedef struct {
    s8 unk0;
    s8 unk1;
    s8 unk2;
    u8 unk3;
    s8 unk4;
    s8 unk5;
    u8 unk6;
    s8 unk7;
    s16 unk8;
    s16 unkA;
    u8 unkC;
    u8 unkD;
} AAD_8002D010;

extern Maps current_map;
extern s8 D_bonus_8002DEF0[];
#define D_bonus_8002D92C (*(volatile s8 *)0x8002D92C)

extern u8 D_global_asm_80750AD4;
extern Gfx **D_1000118;
extern Gfx* printText(Gfx* dl, s16 x, s16 y, f32 scale, u8* string);

typedef struct HandleAAD {
    Actor *reels[4];
    s16 unk10;
    s16 unk12;
    s16 unk14;
    s16 unk16;
    s8 unk18;
    u8 unk19;
    u8 unk1A;
    s8 unk1B;
    u8 unk1C;
    u8 unk1D;
    u8 unk1E;
    s8 unk1F;
    void *unk20;
} HandleAAD;

void func_global_asm_806A2A10(s32, s32, s32);
s16 func_global_asm_806FDB8C(s16, u8*, u8, f32, f32, f32);
void func_global_asm_80737924(void *);
extern void *D_global_asm_807457E4[];
extern Actor *gCurrentActorPointer;
extern Actor *gCurrentPlayer;
extern void playSong(MUSIC_E arg0, f32 arg1); 
extern void func_global_asm_806FDAB8(s16 arg0, f32 arg1);
extern Actor* func_bonus_800253E4(s32 model, s16 x, s16 y, s16 z);
extern u8 setAction(s16 actionIndex, Actor *actor, u8 playerIndex);
extern void func_global_asm_8061C6A8(Actor *arg0, Actor *arg1, u8 arg2, s16 arg3, s16 arg4, s16 arg5, s16 arg6, s16 arg7, s16 arg8, s16 arg9, f32 arg10);
extern s32 playCutscene(Actor *arg0, s16 arg1, u8 arg2);
extern u8 is_cutscene_active;
extern void loadText(Actor *arg0, u16 fileIndex, u8 textIndex);
extern SpriteData D_global_asm_80720CF0;
extern void playSoundAtActorPosition(Actor *arg0, s16 arg1, u8 arg2, s16 arg3, u8 arg4);
extern void func_global_asm_806A2B08(Actor *arg0);
extern void func_bonus_800256C4(HandleAAD *arg0, u8 arg1);
extern void func_global_asm_8061C464(Actor *arg0, Actor *arg1, u8 arg2, s16 arg3, s16 arg4, s16 arg5, s16 arg6, s16 arg7, s16 arg8, s16 arg9, f32 argA);
extern void func_bonus_800254B0(s16 x, s16 y, s16 z, s16 count);
extern u8 func_bonus_80025480(HandleAAD *arg0, u8 arg1);
extern void func_bonus_800264E0(u8 arg0, u8 textIndex);
extern void func_global_asm_8069D2AC(u8 arg0, s16 arg1, s16 arg2, u8 *arg3, u16 arg4, u16 arg5, u8 arg6, u8 arg7);
extern void func_bonus_8002563C(HandleAAD *arg0);
extern void addActorToTextOverlayRenderArray(void *arg0, Actor *arg1, u8 arg2);
extern void renderActor(Actor *arg0, u8 arg1);
extern void func_bonus_800265C0(u8 arg0, u8 textIndex);
extern void func_global_asm_806F8004(f32 xRotation, f32 yRotation, f32 *xOut, f32 *yOut, f32 *zOut);