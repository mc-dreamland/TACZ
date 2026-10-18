-- SG-01 / TaCZ 1.1.8：第一人称动画状态机。
-- display 引用："state_machine": "bujidao:sg_01_state_machine"
-- 实际弹药由 data/bujidao/scripts/sg_01_gun_logic.lua 管理。
-- 保留 static_idle 基准和原有 shoot / bolt 关键帧。
local default = require("tacz_default_state_machine")

-- 默认模块为共享模块，复制可变状态，避免影响其他枪械。
local function copy(value, seen)
    if type(value) ~= "table" then return value end
    seen = seen or {}
    if seen[value] then return seen[value] end
    local result = {}
    seen[value] = result
    for key, item in pairs(value) do result[key] = copy(item, seen) end
    return result
end

local M = copy(default)
local STATIC = M.STATIC_TRACK_LINE
local MAIN = M.MAIN_TRACK
local idle = M.main_track_states.idle
local reload = {}
local ending = {}
local bolt = {}
M.main_track_states.reload = reload
M.main_track_states.reload_end = ending
M.main_track_states.bolt = bolt
M.INPUT_SG01_END = "sg01_reload_end"
M.INPUT_SG01_IDLE = "sg01_idle"
M.INPUT_SG01_WALK = "sg01_reload_walk"

local function track(context)
    return context:getTrack(STATIC, MAIN)
end

local function param(context, key, fallback)
    local params = context:getStateMachineParams() or {}
    local value = params[key]
    if value == nil then return fallback end
    return value
end

local function duration(context, key, fallback)
    local value = tonumber(param(context, key, fallback))
    if not value or value <= 0 then value = fallback end
    return value * 1000
end

local function run_phase(state, context, name, start, length, blend)
    state.phase = name
    state.deadline = start + length
    context:runAnimation(name, track(context), false, PLAY_ONCE_HOLD, blend)
    -- 低帧率下保留时间轴进度，不让每轮装填不断累积延迟。
    local late = context:getCurrentTimestamp() - start
    if late > 0 then context:adjustAnimationProgress(track(context), late / 1000, false) end
end

-- 跑步中按换弹：立即转入默认 walk，再开始逐发装填。
-- 只控制 SG-01 的动画状态；实际走路速度沿用 TaCZ 的换弹限制。
local movement = M.movement_track_states
local run_transition = movement.run.transition
local run_update = movement.run.update
local walk_transition = movement.walk.transition
local movement_idle_transition = movement.idle.transition

function movement.run.transition(this, context, input)
    if input == INPUT_RELOAD or input == this.INPUT_SG01_WALK
        or (this.sg01.reloading and input ~= INPUT_IDLE) then
        return this.movement_track_states.walk
    end
    return run_transition(this, context, input)
end

function movement.run.update(this, context)
    if this.sg01.reloading then
        context:trigger(this.INPUT_SG01_WALK)
        return
    end
    run_update(this, context)
end

function movement.walk.transition(this, context, input)
    -- 刚退出疾跑时仍可能收到 RUN；装填和收尾期间保持 walk。
    if this.sg01.reloading and input == INPUT_RUN then return nil end
    return walk_transition(this, context, input)
end

function movement.idle.transition(this, context, input)
    if this.sg01.reloading and input == INPUT_RUN then
        return this.movement_track_states.walk
    end
    return movement_idle_transition(this, context, input)
end

function idle.transition(this, context, input)
    if input == INPUT_RELOAD then
        local total = context:getAmmoCount() + (context:hasBulletInBarrel() and 1 or 0)
        if total >= context:getMaxAmmoCount() then return nil end
        return this.main_track_states.reload
    end
    if input == INPUT_SHOOT then
        -- shoot 已有模型弹壳向右飞出的动作，不再额外生成一枚弹壳。
        return this.main_track_states.idle
    end
    if input == INPUT_BOLT then
        if this.sg01.pending_bolt then
            -- 同一次射击的拉泵已在 shoot 内完成；只让服务端完成上膛。
            this.sg01.pending_bolt = false
            return this.main_track_states.idle
        end
        return this.main_track_states.bolt
    end
    if input == INPUT_INSPECT and context:getAimingProgress() < 1 then
        -- SG-01 只有 inspect，不调用缺失的 inspect_empty。
        context:runAnimation("inspect", track(context), false, PLAY_ONCE_STOP, 0.2)
        return this.main_track_states.inspect
    end
    return default.main_track_states.idle.transition(this, context, input)
end

function reload.entry(this, context)
    local state = this.main_track_states.reload
    this.sg01.pending_bolt = false
    this.sg01.reloading = true
    context:trigger(this.INPUT_SG01_WALK)
    -- 空仓 intro 装入枪膛的一发也占总容量；只循环填满剩余弹仓。
    state.need = math.max(0, context:getMaxAmmoCount() - context:getAmmoCount() - 1)
    state.started = 0
    state.seen_server = false
    local empty = not context:hasBulletInBarrel()
    local name = empty and "reload_intro_empty" or "reload_intro"
    local length = empty and duration(context, "intro_empty", 2.0) or duration(context, "intro", 0.72)
    run_phase(state, context, name, context:getCurrentTimestamp(), length, 0.12)
end

function reload.exit(this, context)
    this.sg01.reloading = false
end

function reload.update(this, context)
    local state = this.main_track_states.reload
    local server = context:getReloadStateType()
    if server == EMPTY_RELOAD_FEEDING or server == TACTICAL_RELOAD_FEEDING then
        state.seen_server = true
    elseif server == EMPTY_RELOAD_FINISHING or server == TACTICAL_RELOAD_FINISHING
        or (state.seen_server and server == NOT_RELOADING) then
        context:trigger(this.INPUT_SG01_END)
        return
    end
    local now = context:getCurrentTimestamp()
    while now >= state.deadline do
        if state.started >= state.need or not context:hasAmmoToConsume()
            or context:getAmmoCount() + (context:hasBulletInBarrel() and 1 or 0) >= context:getMaxAmmoCount() then
            context:trigger(this.INPUT_SG01_END)
            return
        end
        state.started = state.started + 1
        run_phase(state, context, "reload_loop", state.deadline, duration(context, "loop", 0.86), 0)
    end
end

function reload.transition(this, context, input)
    -- 疾跑和射击共用取消信号；与服务端一致，保持装填直到自然结束。
    if input == INPUT_CANCEL_RELOAD then return nil end
    if input == this.INPUT_SG01_END then
        return this.main_track_states.reload_end
    end
    if input == INPUT_PUT_AWAY then return idle.transition(this, context, input) end
    -- 服务端已允许开火时，及时释放主轨道，不叠着换弹姿势射击。
    if input == INPUT_SHOOT then
        context:stopAnimation(track(context))
        return idle.transition(this, context, input)
    end
    return nil
end

function ending.entry(this, context)
    this.sg01.reloading = true
    context:runAnimation("reload_end", track(context), false, PLAY_ONCE_STOP, 0)
end

function ending.exit(this, context)
    this.sg01.reloading = false
end

function ending.update(this, context)
    if context:isStopped(track(context)) then context:trigger(this.INPUT_SG01_IDLE) end
end

function ending.transition(this, context, input)
    if input == this.INPUT_SG01_IDLE then return this.main_track_states.idle end
    if input == INPUT_PUT_AWAY then return idle.transition(this, context, input) end
    if input == INPUT_SHOOT then
        context:stopAnimation(track(context))
        return idle.transition(this, context, input)
    end
    return nil
end

function bolt.entry(this, context)
    local state = this.main_track_states.bolt
    state.started = context:getCurrentTimestamp()
    state.popped = false
    context:runAnimation("bolt", track(context), false, PLAY_ONCE_STOP, 0.12)
end

function bolt.update(this, context)
    local state = this.main_track_states.bolt
    if not state.popped and context:getCurrentTimestamp() - state.started
        >= duration(context, "bolt_shell_ejecting_time", 0.43) then
        context:popShellFrom(0)
        state.popped = true
    end
    if context:isStopped(track(context)) then context:trigger(this.INPUT_SG01_IDLE) end
end

function bolt.transition(this, context, input)
    if input == this.INPUT_SG01_IDLE then return this.main_track_states.idle end
    if input == INPUT_PUT_AWAY then return idle.transition(this, context, input) end
    -- 实际入膛后，允许新动作接管拉栓的回位尾段。
    if input == INPUT_SHOOT or input == INPUT_RELOAD then
        context:stopAnimation(track(context))
        return idle.transition(this, context, input)
    end
    return nil
end

function M.gun_kick_state.transition(this, context, input)
    if input == INPUT_SHOOT then
        this.sg01.pending_bolt = param(context, "shoot_includes_bolt", true)
        -- 快速连点时替换上一发的缓冲尾段，避免手臂、泵柄和弹壳重复叠加。
        if this.sg01.shot_track then context:stopAnimation(this.sg01.shot_track) end
        local shot_track = context:findIdleTrack(this.GUN_KICK_TRACK_LINE, false)
        this.sg01.shot_track = shot_track
        -- 显式指定只播一次，即使源动画文件的 loop 为 true 也不循环射击。
        context:runAnimation("shoot", shot_track, true, PLAY_ONCE_STOP, 0)
        -- 不延长冷却；服务端入膛状态决定什么时候可以再次开火。
    end
    return nil
end

function M:initialize(context)
    default.initialize(self, context)
    self.sg01 = {pending_bolt = false, reloading = false}
end

return M
