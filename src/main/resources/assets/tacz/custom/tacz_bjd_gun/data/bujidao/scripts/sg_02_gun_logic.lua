-- SG-02 loads one shell at each checkpoint; the chamber belongs to total capacity.
local gun = {}

local function milliseconds(api, key)
    return math.max(1, (tonumber(api:getScriptParams()[key]) or 0) * 1000)
end

local function loaded(api)
    return api:getAmmoCountInMagazine() + (api:hasAmmoInBarrel() and 1 or 0)
end

function gun.start_reload(api)
    if loaded(api) >= api:getMaxAmmoCount() or not api:hasAmmoToConsume() then return false end
    api:cacheScriptData({empty = not api:hasAmmoInBarrel(), phase = 'intro',
        since = api:getCurrentTimestamp(), inserted = false})
    return true
end

function gun.interrupt_reload(api)
    -- Accepted reloads finish their existing phases, just like SG-01.
end

function gun.tick_reload(api)
    local progress = api:getCachedScriptData()
    if not progress then return 0, -1 end
    local now = api:getCurrentTimestamp()
    for _ = 1, 32 do
        local phase = progress.phase
        local length, feed
        if phase == 'intro' then
            length = milliseconds(api, progress.empty and 'intro_empty' or 'intro')
            feed = milliseconds(api, progress.empty and 'intro_empty_feed' or 'intro_feed')
        elseif phase == 'loop' then
            length = milliseconds(api, 'loop')
            feed = milliseconds(api, 'loop_feed')
        else
            length = milliseconds(api, 'ending')
        end
        local elapsed = now - progress.since
        if feed and not progress.inserted and elapsed >= feed then
            progress.inserted = true
            if loaded(api) >= api:getMaxAmmoCount()
                    or (api:isReloadingNeedConsumeAmmo() and api:consumeAmmoFromPlayer(1) ~= 1) then
                progress.no_more = true
            elseif api:hasAmmoInBarrel() then api:putAmmoInMagazine(1)
            else api:setAmmoInBarrel(true) end
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
        else progress.phase = 'loop' end
    end
    api:cacheScriptData(progress)
    return progress.empty and 1 or 3, 1
end

return gun
