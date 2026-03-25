package com.emma.endinv.options;

public enum CreationEndinvStrategy {
    CREATE_PER_PLAYER,  // 为每个玩家创建
    USE_GLOBAL_SHARED,  // 使用共享
    NONE                // 不创建
}
