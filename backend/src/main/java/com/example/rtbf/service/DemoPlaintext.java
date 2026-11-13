package com.example.rtbf.service;

import java.util.Map;

/**
 * 演示库业务表只保存标识符哈希，掩码展示需要明文。
 * 仅用于本演示环境的固定三主体数据；生产环境应在入库时同列保存掩码值。
 */
final class DemoPlaintext {

    private static final Map<String, String> PHONE = Map.of(
        "b1c4769e3ad14f68ea1a96b73bbe5a83d90792f044828c5daf9d908b0738b177", "13800000001",
        "965eac4d7b0698a18e3c59c34023d9cd529752c7e5fd4164209d2e7df010e2ad", "13700000003",
        "412d65b4d8f37676654685a6a525363a8a635ed140a69ac30a30f344ae0dd3b1", "13900000002",
        "d694cdadfdf802a16d1fdd46fa055fec33cd5a3ce2be6ed69b2681fce087da0b", "13600000004");

    private static final Map<String, String> EMAIL = Map.of(
        "dae92ea63028947efcc58b3ef81d8bff37a0782f9d253fab62c55600fa36569c", "zhang.wei@example.com",
        "4a61631b42f1a4e8d8a1314e92bb7dc5ed9a8bde490996c2e9d291a91978ab77", "zhangwei.work@example.com",
        "7440664a70a8af65857729eca8a636473994304b971a19aee68e4d6ba6209afe", "lina@example.com",
        "d5b6231d61840686113e975d519b97952bdd8803b28a1317ab692c5ace13c214", "zhang.wei.other@example.com",
        "62d117feff6a60bea44377630038407de043f04c31fdf2ab07358673fc143486", "wang.qiang@example.com");

    private static final Map<String, String> IDCARD = Map.of(
        "04e7358a07d7a12d426f8a3091293b19fd7a278b56163c5e9f7f0c7b41e7d233", "110101199001011234",
        "a844c45c60b52158aeb01e241e2c98bce9cfd583035c671c9ac08c19d7927df6", "440106199505056789",
        "7fe255b84945232919b4669bdaef6136e510a0dd6ce47b4b520c8d88ede5ad2d", "310104199203034567",
        "2d4300c50325ad08322aba60f07c7067a06d94ac2ef9be4ce1b023cc5e8bcb0f", "330106199606061234",
        "3c3741be8bfd3e18e6a125e8f3611629651e5e605deacd6db4fa851cff7db27f", "510104199209097777");

    private DemoPlaintext() {
    }

    static String phone(String hash) {
        return PHONE.getOrDefault(hash, "138****0000");
    }

    static String email(String hash) {
        return EMAIL.getOrDefault(hash, "***@example.com");
    }

    static String idcard(String hash) {
        return IDCARD.getOrDefault(hash, "1101**********1234");
    }
}
