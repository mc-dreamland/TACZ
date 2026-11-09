-- BJD animation driver, authored against AnimationStateContext's public API.
-- Sprint overrides are isolated from the original holding and gun-action tracks.
-- states() creates per-display closures: the loaded module has no mutable gun state.
local driver = {}

function driver.states()
    local s = {action = nil, moving = nil, reload = nil}
    local state = {}

    local function clock(ctx)
        return ctx:getCurrentTimestamp() / 1000
    end

    local function duration(ctx, clip)
        return tonumber(ctx:getStateMachineParams()[clip]) or 0
    end

    local function play(ctx, clip, track, additive, kind, blend)
        if not ctx:hasAnimationPrototype(clip) then return false end
        ctx:runAnimation(clip, track, additive, kind, blend or 0.08)
        return true
    end

    local function action(ctx, clip)
        ctx:stopAnimation(s.shot)
        if s.moving == 'run' then ctx:resumeAnimation(s.motion) end
        ctx:stopAnimation(s.motion)
        s.moving = nil
        if play(ctx, clip, s.main, false, 0) then
            s.action = clip
            s.until_time = clock(ctx) + duration(ctx, clip)
        else
            s.action = nil
        end
    end

    local function clear_action(ctx)
        ctx:stopAnimation(s.main)
        s.action = nil
        s.reload = nil
    end

    local function clear_pump_cycle()
        s.pending_pumps = 0
        s.shot_until = nil
    end

    local function can_start_reload(ctx)
        local capacity = ctx:getMaxAmmoCount()
        local amount = ctx:getAmmoCount()
        -- SG-01 includes the chamber in its nominal total capacity.
        -- Other guns must have a free magazine slot even when the chamber is empty.
        if ctx:getStateMachineParams().shell_reload and ctx:hasBulletInBarrel() then
            amount = amount + 1
        end
        return capacity > 0 and amount < capacity and ctx:hasAmmoToConsume()
    end

    local function begin_reload(ctx, reload_type)
        -- A local request is not acceptance. Rejected requests have no visual phase,
        -- so they must never fall through to a fabricated reload_end animation.
        if reload_type == 0 or not can_start_reload(ctx) then return end
        clear_pump_cycle()
        s.saw_reload = true
        s.reload_started = clock(ctx)
        local empty = reload_type == 1 or reload_type == 2
        if ctx:getStateMachineParams().shell_reload then
            s.reload = 'intro'
            action(ctx, empty and 'reload_intro_empty' or 'reload_intro')
        else
            s.reload = 'magazine'
            action(ctx, empty and 'reload_empty' or 'reload_tactical')
        end
    end

    local function finish_shell_reload(ctx)
        s.reload = 'ending'
        action(ctx, 'reload_end')
    end

    function state.entry(_, ctx)
        ctx:ensureTrackLineSize(4)
        s.base = ctx:getAsSingletonTrack(0)
        s.motion = ctx:getAsSingletonTrack(1)
        s.main = ctx:getAsSingletonTrack(2)
        s.shot = ctx:getAsSingletonTrack(3)
        play(ctx, 'static_idle', s.base, false, 0, 0)
        action(ctx, 'draw')
    end

    function state.transition(_, ctx, input)
        if input == 'put_away' then
            clear_pump_cycle()
            s.reload = nil
            action(ctx, 'put_away')
        elseif input == 'draw' then
            clear_pump_cycle()
            s.reload = nil
            action(ctx, 'draw')
        elseif input == 'reload' then
            if not s.reload then begin_reload(ctx, ctx:getReloadStateType()) end
        elseif input == 'cancel_reload' then
            -- Sprint can request cancellation even when the server keeps reloading.
            -- update() follows the actual reload state, including shell-reload ending.
            return nil
        elseif input == 'shoot' then
            if s.reload or ctx:getReloadStateType() ~= 0 then return nil end
            if s.moving == 'run' or s.moving == 'run_start' or s.moving == 'run_end' then
                ctx:resumeAnimation(s.motion)
                ctx:stopAnimation(s.motion)
                s.moving = nil
                s.requested_motion = 'idle'
            end
            clear_action(ctx)
            play(ctx, 'shoot', s.shot, true, 1, 0)
            if ctx:getStateMachineParams().integrated_pump then
                s.shot_until = clock(ctx) + duration(ctx, 'shoot')
                -- With reserve rounds, the next bolt event belongs to this shot.
                -- Keep the token past the clip end so a delayed event cannot replay it.
                if ctx:getAmmoCount() > 0 then
                    s.pending_pumps = (s.pending_pumps or 0) + 1
                end
            end
        elseif input == 'blot' or input == 'bolt' then
            -- The engine's historical input name is "blot".
            if s.reload or ctx:getReloadStateType() ~= 0 then return nil end
            if ctx:getStateMachineParams().integrated_pump and (s.pending_pumps or 0) > 0 then
                s.pending_pumps = s.pending_pumps - 1
                return nil
            end
            action(ctx, 'bolt')
        elseif input == 'inspect' and not s.reload and ctx:getReloadStateType() == 0 then
            local empty = ctx:getAmmoCount() == 0 and not ctx:hasBulletInBarrel()
            local clip = empty and ctx:hasAnimationPrototype('inspect_empty') and 'inspect_empty' or 'inspect'
            action(ctx, clip)
        elseif input == 'run' or input == 'walk' or input == 'idle' then
            s.requested_motion = input
        end
        return nil
    end

    function state.update(_, ctx)
        local now = clock(ctx)
        local reload_type = ctx:getReloadStateType()
        -- Recover the reload pose if its event was missed or the display was reinitialized.
        if reload_type ~= 0 and not s.reload and s.action ~= 'put_away' then
            begin_reload(ctx, reload_type)
        end
        if s.reload then
            if reload_type ~= 0 then s.saw_reload = true end
            if s.reload ~= 'ending' and ctx:getStateMachineParams().shell_reload
                    and (reload_type == 2 or reload_type == 4) then
                finish_shell_reload(ctx)
            elseif s.reload ~= 'ending' and reload_type == 0 and s.saw_reload then
                if ctx:getStateMachineParams().shell_reload then finish_shell_reload(ctx)
                else clear_action(ctx) end
            end
        end

        if s.action and now >= s.until_time then
            if s.reload and ctx:getStateMachineParams().shell_reload then
                if s.reload == 'ending' then
                    -- Hold the last frame until the server also leaves the ending phase.
                    if reload_type == 0 then clear_action(ctx) end
                else
                    s.reload = 'loop'
                    action(ctx, 'reload_loop')
                end
            elseif not s.reload or reload_type == 0 then
                clear_action(ctx)
            end
        end

        if reload_type ~= 0 or s.reload or s.action
                or (s.shot_until and now < s.shot_until) or ctx:getAimingProgress() > 0.05 then
            if s.moving == 'run' then ctx:resumeAnimation(s.motion) end
            ctx:stopAnimation(s.motion)
            s.moving = nil
            return
        end
        local motion = s.requested_motion or 'idle'
        -- Only the retained sprint clips use an absolute, self-contained pose.
        -- Normal holding, walking and gun actions keep the original tracks above.
        if ctx:hasAnimationPrototype('run_start') and ctx:hasAnimationPrototype('run_end') then
            local params = ctx:getStateMachineParams()
            if motion == 'run' then
                if s.moving ~= 'run' and s.moving ~= 'run_start' then
                    play(ctx, 'run_start', s.motion, false, 0, 0)
                    s.moving = 'run_start'
                    s.motion_until = now + (tonumber(params.run_enter) or 0.18)
                elseif s.moving == 'run_start' and now >= s.motion_until then
                    play(ctx, 'run', s.motion, false, 2, 0)
                    s.moving = 'run'
                    s.walk_anchor = ctx:getWalkDist()
                end
                if s.moving == 'run' then
                    ctx:pauseAnimation(s.motion)
                    local distance = math.max(0, ctx:getWalkDist() - s.walk_anchor)
                    ctx:setAnimationProgress(s.motion, (distance / (tonumber(params.run_stride) or 2.35)) % 1, true)
                end
                return
            elseif s.moving == 'run' or s.moving == 'run_start' then
                play(ctx, 'run_end', s.motion, false, 0, 0)
                s.moving = 'run_end'
                s.motion_until = now + (tonumber(params.run_exit) or 0.16)
                return
            elseif s.moving == 'run_end' then
                if now < s.motion_until then return end
                -- Absolute sprint endpoints already match static_idle. Replace directly
                -- before returning to additive idle/walk, avoiding a doubled base pose.
                if play(ctx, motion, s.motion, true, 2, 0) then s.moving = motion end
                return
            end
        end
        if motion ~= s.moving then
            if play(ctx, motion, s.motion, true, 2, 0.12) then s.moving = motion end
        end
    end

    return {state}
end

return driver
