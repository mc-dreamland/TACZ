-- Independent SG-01 gameplay adapter. All timings come from Bujidao script_param.
local gun = {}

local function milliseconds(api, key)
    return math.max(0, tonumber(api:getScriptParams()[key]) or 0) * 1000
end

local function loaded(api)
    return api:getAmmoCountInMagazine() + (api:hasAmmoInBarrel() and 1 or 0)
end

local function insert_shell(api)
    if loaded(api) >= api:getMaxAmmoCount() then return false end
    if api:isReloadingNeedConsumeAmmo() and api:consumeAmmoFromPlayer(1) ~= 1 then return false end
    if api:hasAmmoInBarrel() then api:putAmmoInMagazine(1)
    else api:setAmmoInBarrel(true) end
    return true
end

function gun.shoot(api)
    api:shootOnce(api:isShootingNeedConsumeAmmo())
    api:cacheScriptData({kind = 'shot', fired_at = api:getCurrentTimestamp()})
end

function gun.start_bolt(api)
    local previous = api:getCachedScriptData()
    local wait = milliseconds(api, 'bolt_feed_time')
    if previous and previous.kind == 'shot' and api:getScriptParams().shoot_includes_bolt ~= false then
        wait = math.max(0, milliseconds(api, 'shoot_feed_time') - (api:getCurrentTimestamp() - previous.fired_at))
    end
    api:cacheScriptData({kind = 'pump', wait = wait})
    return true
end

function gun.tick_bolt(api)
    local progress = api:getCachedScriptData()
    if not progress or progress.kind ~= 'pump' then return false end
    if api:getBoltTime() < progress.wait then return true end
    if not api:hasAmmoInBarrel() and api:removeAmmoFromMagazine(1) == 1 then api:setAmmoInBarrel(true) end
    api:cacheScriptData(nil)
    return false
end

function gun.start_reload(api)
    if loaded(api) >= api:getMaxAmmoCount() or not api:hasAmmoToConsume() then return false end
    api:cacheScriptData({kind = 'reload', empty = loaded(api) == 0, phase = 'intro',
        since = api:getCurrentTimestamp(), inserted = false})
    return true
end

function gun.interrupt_reload(api)
    -- Accepted SG-01 reloads complete normally. Sprint/attack cancellation intents
    -- may repeat every tick; none may restart the ending or consume another shell.
    -- The engine clears the reload independently when the held item is replaced.
end

function gun.tick_reload(api)
    local progress = api:getCachedScriptData()
    if not progress or progress.kind ~= 'reload' then return 0, -1 end
    local now = api:getCurrentTimestamp()
    -- Catch up elapsed phases without feeding any shell more than once.
    for _ = 1, 32 do
        local phase = progress.phase
        local length
        local feed
        if phase == 'intro' then
            length = milliseconds(api, progress.empty and 'intro_empty' or 'intro')
            if progress.empty then feed = milliseconds(api, 'intro_empty_feed') end
        elseif phase == 'loop' then
            length = milliseconds(api, 'loop')
            feed = milliseconds(api, 'loop_feed')
        else
            length = milliseconds(api, 'ending')
        end
        local elapsed = now - progress.since
        if feed and not progress.inserted and elapsed >= feed then
            progress.inserted = true
            progress.no_more = not insert_shell(api)
        end
        if elapsed < length then
            api:cacheScriptData(progress)
            local ordinal = progress.empty and 1 or 3
            if phase == 'ending' then ordinal = ordinal + 1 end
            return ordinal, math.max(1, math.ceil(length - elapsed))
        end
        if phase == 'ending' then
            api:cacheScriptData(nil)
            return 0, -1
        end
        progress.since = progress.since + length
        progress.inserted = false
        if progress.no_more or loaded(api) >= api:getMaxAmmoCount() or not api:hasAmmoToConsume() then
            progress.phase = 'ending'
        else
            progress.phase = 'loop'
        end
    end
    api:cacheScriptData(progress)
    return progress.empty and 1 or 3, 1
end

return gun
