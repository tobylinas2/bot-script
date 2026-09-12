#!/bin/bash
# 心形演示环境重置：拆旧箱 → 建原材料箱并装入粉色羊毛 → 建目标双箱（补全双箱即触发摆放）
# 用法：bash examples/heart/reset-demo.sh
# 前提：Paper 服务器已开启 RCON（见主 README「实连验收」）；坐标与 demo-*.yaml 保持一致
set -u
cd "$(dirname "$0")/../.."    # 仓库根（tools/rcon.mjs 相对路径）

RCON_HOST=127.0.0.1
RCON_PORT=25575
RCON_PASS=botscript
MATERIAL=pink_wool

SRC="119 151 -306"            # 原材料箱
DST_R="123 151 -300"          # 目标双箱西半（facing=south 时 type=right 在西侧）
DST_L="124 151 -300"          # 目标双箱东半（type=left）

R="node tools/rcon.mjs $RCON_HOST $RCON_PORT $RCON_PASS"

$R "setblock $DST_R air" > /dev/null;  sleep 0.5
$R "setblock $DST_L air" > /dev/null;  sleep 0.5
$R "setblock $SRC air"   > /dev/null;  sleep 0.5
$R "setblock $SRC minecraft:chest[facing=south]" > /dev/null; sleep 0.5
$R "item replace block $SRC container.0 with minecraft:$MATERIAL 32" > /dev/null; sleep 0.5
$R "setblock $DST_R minecraft:chest[facing=south,type=right]" > /dev/null; sleep 0.5
$R "setblock $DST_L minecraft:chest[facing=south,type=left]"  > /dev/null; sleep 0.5

echo "dst_r: $($R "data get block $DST_R id" | head -c 60)"
echo "dst_l: $($R "data get block $DST_L id" | head -c 60)"
echo "src:   $($R "data get block $SRC Items" | head -c 80)"
