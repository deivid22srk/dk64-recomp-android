extern void func_global_asm_8061B4E4();
extern void func_global_asm_8061B660(void*, f32*, f32*, f32*, f32, f32, f32, f32);
extern void func_global_asm_8061B7E0(Actor*, void*, f32, f32);
extern void func_global_asm_8061C0FC(void*);
extern void func_global_asm_8061D060(void*);
extern void func_global_asm_8061D1FC(Actor*);
extern void func_global_asm_8061D6A8(void*);
extern void func_global_asm_8061EDA0(void*, f32*, f32*, f32*, s32, s32);
extern void func_global_asm_8061F164(void*, s32);
extern void func_global_asm_80622334(Actor*, s16, f32*);
extern void func_global_asm_80622B24(Actor*, f32*, f32*, f32*, void*, void*, void*, Actor*);
extern void func_global_asm_80625320(Actor*, f32*, f32*, f32*, f32*, f32*, f32*);
extern void func_global_asm_80627490(f32*, f32*, f32, f32, f32, f32, f32, f32);
extern void func_global_asm_80627F04(s32, s32, s32, u16);
extern void func_global_asm_8061D898(void);
extern u8 func_global_asm_8061B4B0(void);
extern void func_global_asm_80602498(void);
extern s16 func_global_asm_806CC190(s16 arg0, s16 arg1, f32 arg2);
extern u8 getBonePosition(Actor *actor, s32 boneIndex, f32 *x, f32 *y, f32 *z);
extern void playSong(MUSIC_E arg0, f32 arg1);
extern int gameIsInDKTVMode(void);
extern void func_global_asm_8061C39C(Actor *camera);
extern void func_global_asm_8061D4E4(Actor *arg0);
extern s16 func_global_asm_80665DE0(f32 arg0, f32 arg1, f32 arg2, f32 arg3);
extern void func_global_asm_8062217C(Actor*, s16);
extern void func_global_asm_8060098C(void *arg0, s32 arg1, s32 arg2, s32 arg3, s32 arg4);
extern void func_global_asm_80625994(Actor *arg0, f32 arg1, f32 *arg2, f32 *arg3, f32 *arg4);
extern f32 func_global_asm_80612D10(f32 arg0);
extern f32 func_global_asm_80612D1C(f32 arg0);
extern void func_global_asm_8061DA14(s32 arg0, s32 arg1, s32 arg2);
extern f32 func_global_asm_80612790(s16 arg0);
extern f32 func_global_asm_80612794(s16 arg0);

typedef struct CutsceneBank_unk0 {
    u8 pad0[4];
    void *unk4;
} CutsceneBank_unk0;

typedef union FuncBank_value {
    s32 vals32;
    struct {
        u16 valu16_0;
        u16 valu16_1;
    };
} FuncBank_value;

typedef struct CutsceneBank_FuncBank {
    u8 unk0;
    u8 command;
    u8 unk2;
    u8 unk3;
    FuncBank_value params[3];
    u8 pad10[4];
} CutsceneBank_FuncBank;

typedef struct CutsceneBank_CamBank {
    s16 point_count;
    s16 unk2;
    s16 *point_array;
    s16 *length_array;
} CutsceneBank_CamBank;

typedef struct {
    s32 unk0;
    s32 unk4;
    s32 unk8;
    s32 unkC;
    s16 unk10;
    s16 unk12;
    s16 unk14;
    s16 unk16;
    s32 unk18;
} CutsceneBank_LockRegion;

typedef struct CutsceneBank {
    CutsceneBank_unk0 unk0[24];
    s16 lock_count;
    u8 padC2[2];
    CutsceneBank_LockRegion *lock_regions;
    u8 *lock_chunks;
    s16 cutscene_count;
    u8 padCE[2];
    CutsceneBank_CamBank *camera_bank;
    u8 unkD4[4];
    CutsceneBank_FuncBank *function_bank;
    f32 unkDC;
} CutsceneBank;

extern f32 D_global_asm_807476A4;
extern OSTime D_global_asm_807476C8;
extern OSTime D_global_asm_807476D0;
extern s16 D_global_asm_807476DC;
extern s16 D_global_asm_807476E0;
extern s16 D_global_asm_807476E4;
extern s16 D_global_asm_807476E8;
extern s16 D_global_asm_807476F0;
extern s16 D_global_asm_807476F4;
extern CutsceneBank* D_global_asm_807476FC;
extern CutsceneBank D_global_asm_807F5B10[];
extern s16 D_global_asm_807F5CD0;
extern Actor* D_global_asm_807F5CE8;
extern s16 D_global_asm_807F5CEC;
extern u16 D_global_asm_807F5CEE;
extern u16 D_global_asm_807F5CF0;
extern u16 D_global_asm_807F5CF2;
extern u16 D_global_asm_807F5CF4;
extern f32 D_global_asm_807F5CFC;
extern f32 D_global_asm_807F5D00;
extern Actor* D_global_asm_807F5D0C;
extern f32 loading_zone_transition_speed;
extern Actor* gPlayerPointer;
extern PlayerAdditionalActorData *extra_player_info_pointer;