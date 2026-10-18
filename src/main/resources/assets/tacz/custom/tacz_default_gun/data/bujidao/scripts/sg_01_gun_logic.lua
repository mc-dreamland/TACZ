-- SG-01 / TaCZ 1.1.8：服务端逐发装填与泵动上膛逻辑。
-- gun data 引用："script": "bujidao:sg_01_gun_logic"
-- 放在 data/bujidao/scripts 下；assets 中的状态机只负责播放动画。
local M = {}

local function timing(api)
    local p = api:getScriptParams() or {}
    local function ms(key, fallback)
        local value = tonumber(p[key])
        if not value or value <= 0 then value = fallback end
        return value * 1000
    end
    local t = {
        intro = ms("intro", 0.72), intro_empty = ms("intro_empty", 2.0),
        loop = ms("loop", 0.86), ending = ms("ending", 0.74),
        intro_empty_feed = ms("intro_empty_feed", 1.07), loop_feed = ms("loop_feed", 0.46),
        bolt_time = ms("bolt_time", 1.15), bolt_feed = ms("bolt_feed_time", 0.72),
        shoot_time = ms("shoot_time", 0.8), shoot_feed = ms("shoot_feed_time", 0.53),
        integrated = p.shoot_includes_bolt ~= false
    }
    t.intro_empty_feed = math.min(t.intro_empty_feed, t.intro_empty)
    t.loop_feed = math.min(t.loop_feed, t.loop)
    return t
end

-- 弹仓和枪膛共用标称弹容量，扩容弹匣同样计入枪膛内的一发。
local function needed(api)
    return math.max(0, api:getNeededAmmoAmount() - (api:hasAmmoInBarrel() and 1 or 0))
end

local function take_one(api)
    if not api:isReloadingNeedConsumeAmmo() then return true end
    if not api:hasAmmoToConsume() then return false end
    return api:consumeAmmoFromPlayer(1) == 1
end

function M.shoot(api)
    local t = timing(api)
    api:shootOnce(api:isShootingNeedConsumeAmmo())
    -- TaCZ 要等射击冷却结束才发起自动拉栓，不能把整段动画加到冷却里。
    -- 下一发由下面的实际入膛时间控制，动画的缓冲尾段不锁住开火。
    api:cacheScriptData({kind = "shot", at = api:getCurrentTimestamp(), integrated = t.integrated})
end

function M.start_bolt(api)
    local t = timing(api)
    local previous = api:getCachedScriptData()
    local feed = t.bolt_feed
    if previous and previous.kind == "shot" and previous.integrated then
        local elapsed = math.max(0, api:getCurrentTimestamp() - previous.at)
        feed = math.max(0, t.shoot_feed - elapsed)
    end
    -- 枪栓复位并入膛的同一 tick 解除拉栓状态，不再等 0.8 / 1.15 秒的尾帧。
    api:cacheScriptData({kind = "bolt", total = feed, feed = feed, fed = false})
    return true
end

function M.tick_bolt(api)
    local c = api:getCachedScriptData()
    if not c or c.kind ~= "bolt" then return false end
    local elapsed = api:getBoltTime()
    if not c.fed and elapsed >= c.feed then
        if not api:hasAmmoInBarrel() and api:removeAmmoFromMagazine(1) == 1 then
            api:setAmmoInBarrel(true)
        end
        c.fed = true
        api:cacheScriptData(c)
    end
    return elapsed < c.total
end

function M.start_reload(api)
    if not api:hasAmmoToConsume() then return false end
    local tactical = api:hasAmmoInBarrel()
    if needed(api) <= 0 then return false end
    api:cacheScriptData({kind = "reload", tactical = tactical, phase = "intro",
        start = 0, fed = false, failed = false})
    return true
end

local function finish(c, at)
    c.phase = "ending"
    c.start = at
end

function M.tick_reload(api)
    local c = api:getCachedScriptData()
    if not c or c.kind ~= "reload" then return NOT_RELOADING, -1 end
    local t = timing(api)
    local now = api:getReloadTime()
    -- 追赶低 TPS 的时间轴；每一发都再次确认库存，避免卡顿时凭空增加子弹。
    while c.phase ~= "ending" do
        local intro = c.phase == "intro"
        local length = intro and (c.tactical and t.intro or t.intro_empty) or t.loop
        local feed = intro and t.intro_empty_feed or t.loop_feed
        if not c.fed and (not intro or not c.tactical) and now >= c.start + feed then
            if intro then
                if not api:hasAmmoInBarrel() and needed(api) > 0 and take_one(api) then
                    api:setAmmoInBarrel(true)
                else
                    c.failed = true
                end
            elseif needed(api) > 0 and take_one(api) then
                api:putAmmoInMagazine(1)
            else
                c.failed = true
            end
            c.fed = true
        end
        if now < c.start + length then break end
        local boundary = c.start + length
        if c.failed or needed(api) <= 0 or not api:hasAmmoToConsume() then
            finish(c, boundary)
        else
            c.phase = "loop"
            c.start = boundary
            c.fed = false
        end
    end
    api:cacheScriptData(c)
    if c.phase == "ending" then
        local remaining = c.start + t.ending - now
        if remaining <= 0 then return NOT_RELOADING, -1 end
        return c.tactical and TACTICAL_RELOAD_FINISHING or EMPTY_RELOAD_FINISHING, remaining
    end
    local length = c.phase == "intro" and (c.tactical and t.intro or t.intro_empty) or t.loop
    local reserved_round = not c.fed and (c.phase == "loop" or not c.tactical) and 1 or 0
    local remaining_rounds = math.max(0, needed(api) - reserved_round)
    local remaining = math.max(0, c.start + length - now) + remaining_rounds * t.loop
    return c.tactical and TACTICAL_RELOAD_FEEDING or EMPTY_RELOAD_FEEDING, remaining
end

function M.interrupt_reload(api)
    -- TaCZ 1.1.8 的疾跑尝试和射击尝试都会调用此接口，无法区分来源。
    -- 沿用默认弹匣枪的处理：装填自然完成，不响应这个取消请求。
    -- 保持 FEEDING，TaCZ 才会持续把疾跑切为走路；射击等待换弹结束。
    -- 客户端同样忽略 INPUT_CANCEL_RELOAD，避免动画提前收尾。
end

return M
