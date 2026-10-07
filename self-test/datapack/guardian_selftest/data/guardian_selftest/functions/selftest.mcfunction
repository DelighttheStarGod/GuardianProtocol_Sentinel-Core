# 自验入口：由 config/forge-server.toml 的 runFunction 在服务端启动完成时自动执行。
#
# 为什么要绕这一道：这个环境**没法把命令喂给服务端控制台**（stdin 管道不通），
# 所以走 Forge 的「条件文件」机制 —— 它在服务端完成启动后自动跑这个函数，
# 于是整条自验链完全无人值守。
#
# 注意顺序：最后一句是 stop，前面必须把四段自验都跑完。

say [selftest] begin
guardianprotocol selftest interval
guardianprotocol selftest targeting
guardianprotocol selftest multi
guardianprotocol selftest block
guardianprotocol selftest path
say [selftest] done
stop
