/**
 *  MIT License
 *  Copyright 2020 Jonathan Bradshaw (jb@nrgup.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
*/

import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import hubitat.helper.ColorUtils
import hubitat.helper.HexUtils
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.*
import java.util.regex.Matcher
import java.util.Random

metadata {
    definition (name: 'Tuya Local RGBW Light', namespace: 'tuya', author: 'Jonathan Bradshaw',
                importUrl: 'https://raw.githubusercontent.com/bradsjm/hubitat-drivers/master/Tuya/Local/TuyaLocalRgbLight.groovy') {
        capability 'Actuator'
        capability 'ColorControl'
        capability 'ColorMode'
        capability 'ColorTemperature'
        capability 'Initialize'
        capability 'Light'
        capability 'LightEffects'
        capability 'Switch'
        capability 'SwitchLevel'
        capability 'Refresh'

        attribute 'retries', 'number'
        attribute 'errors', 'number'

        command 'scanNetwork', [
            [
                name: 'startIp',
                type: 'STRING'
            ],
            [
                name: 'endIp',
                type: 'STRING'
            ]
        ]

        command 'sendCustomDps', [
            [
                name: 'Dps',
                type: 'NUMBER'
            ],
            [
                name: 'Value',
                type: 'STRING'
            ]
        ]
    }
}

preferences {
    section {
        input name: 'ipAddress',
              type: 'text',
              title: 'Device IP',
              required: true

        input name: 'powerDps',
              title: 'Power DPS',
              type: 'number',
              required: true,
              range: '1..255',
              defaultValue: '1'

        input name: 'repeat',
              title: 'Command Retries',
              type: 'number',
              required: true,
              range: '0..5',
              defaultValue: '3'

        input name: 'timeoutSecs',
              title: 'Command Timeout (sec)',
              type: 'number',
              required: true,
              range: '1..5',
              defaultValue: '1'

        input name: 'heartbeatSecs',
              title: 'Heartbeat interval (sec)',
              type: 'number',
              required: true,
              range: '0..60',
              defaultValue: '20'

        input name: 'fxCount',
              title: 'Number of Effects Supported',
              type: 'number',
              required: true,
              range: '0..9',
              defaultValue: '0'

        input name: 'logEnable',
              type: 'bool',
              title: 'Enable debug logging',
              required: false,
              defaultValue: true

        input name: 'txtEnable',
              type: 'bool',
              title: 'Enable descriptionText logging',
              required: false,
              defaultValue: true
    }
}

// Tuya Function Categories
@Field static final Map<String, List<String>> tuyaFunctions = [
    'brightness': [ 'bright_value', 'bright_value_v2', 'bright_value_1' ],
    'colour': [ 'colour_data', 'colour_data_v2' ],
    'switch': [ 'switch_led', 'switch_led_1', 'light' ],
    'temperature': [ 'temp_value', 'temp_value_v2' ],
    'workMode': [ 'work_mode' ]
]

/*
 * Tuya default attributes used if missing from device details
 */
@Field static final Map defaults = [
    'bright_value': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'bright_value_v2': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'temp_value': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'temp_value_v2': [ min: 0, max: 100, scale: 0, step: 1, type: 'Integer' ],
    'colour_data': [
        h: [ min: 1, scale: 0, max: 360, step: 1, type: 'Integer' ],
        s: [ min: 1, scale: 0, max: 255, step: 1, type: 'Integer' ],
        v: [ min: 1, scale: 0, max: 255, step: 1, type: 'Integer' ]
    ],
    'colour_data_v2': [
        h: [ min: 1, scale: 0, max: 360, step: 1, type: 'Integer' ],
        s: [ min: 1, scale: 0, max: 1000, step: 1, type: 'Integer' ],
        v: [ min: 1, scale: 0, max: 1000, step: 1, type: 'Integer' ]
    ]
]

// Constants
@Field static final Integer minMireds = 153
@Field static final Integer maxMireds = 500 // should this be 370?

// Queue used for ACK tracking
@Field static queues = new ConcurrentHashMap<String, SynchronousQueue>()

/**
 *  Hubitat Driver Event Handlers
 */
// Called to keep device connection open
void heartbeat() {
    String id = getDataValue('id')
    String localKey = getDataValue('local_key')
    SynchronousQueue queue = getQ()
    LOG.debug "sending heartbeat"
    synchronized (queue) {
        tuyaSendCommand(id, ipAddress, localKey, null, 'HEART_BEAT')
        if (queue.poll(timeoutSecs, TimeUnit.SECONDS)) {
            LOG.debug "received heartbeat"
        } else {
            LOG.warn "no response to heartbeat"
        }
    }

    if (heartbeatSecs) { runIn(heartbeatSecs, 'heartbeat') }
}

// Called when the device is first created
void installed() {
    LOG.info "driver installed"
}

// Called to initialize
void initialize() {
    sendEvent ([ name: 'retries', value: 0, descriptionText: 'reset' ])
    sendEvent ([ name: 'errors', value: 0, descriptionText: 'reset' ])
    if (fxCount) {
        sendEvent ([ name: 'lightEffects', value: JsonOutput.toJson(
            (1..fxCount).collectEntries { i-> [(i): "scene${i}"]}
        )])
    } else {
        sendEvent ([ name: 'lightEffects', value: "{}" ])
    }
    heartbeat()
}

// Component command to turn on device
void on() {
    LOG.info "switching on"

    if (repeatCommand([ (powerDps as String): true ])) {
        sendEvent([ name: 'switch', value: 'on', descriptionText: 'switch is on' ])
    } else {
        parent?.componentOn(device)
    }
}

// Component command to turn off device
void off() {
    LOG.info "switching off"

    if (repeatCommand([ (powerDps as String): false ])) {
        sendEvent([ name: 'switch', value: 'off', descriptionText: 'switch is off' ])
    } else {
        parent?.componentOff(device)
    }
}

// parse responses from device
void parse(String message) {
    if (!message) { return }
    String localKey = getDataValue('local_key')
    Map result = tuyaDecode(HexUtils.hexStringToByteArray(message), localKey)
    LOG.debug "received ${result}"
    if (result.error) {
        LOG.error "received error ${result.error}"
        increaseErrorCount(result.error)
    } else if (result.commandByte == 7 || result.commandByte == 9) { // COMMAND or HEARTBEAT ACK
        if (!getQ().offer(result)) { LOG.warn "result received without waiting thread" }
    } else if (result.commandByte == 8 || result.commandByte == 10 ) { // STATUS or QUERY RESULTS
        Map json = new JsonSlurper().parseText(result.text)
        parseDeviceState(json.dps)
    }
}

// parse commands from parent (cloud)
void parse(List<Map> description) {
    description.each { d ->
        if (device.currentValue(d.name) != d.value) {
            if (d.descriptionText && txtEnable) { LOG.info "${d.descriptionText}" }
            sendEvent(d)
        }
    }
}

// Component command to refresh device
void refresh() {
    String id = getDataValue('id')
    String localKey = getDataValue('local_key')
    if (!tuyaSendCommand(id, ipAddress, localKey, null, 'DP_QUERY')) {
        parent?.componentRefresh(device)
    } else {
        LOG.info 'refreshed local state'
    }
}

void scanNetwork(String startIp, String endIp) {
    LOG.info "scan network from ${startIp} to ${endIp}"
    scanNetworkAction([
        startIp: ipToLong(startIp),
        endIp: ipToLong(endIp)
    ])
}

void scanNetworkAction(Map data) {
    String id = getDataValue('id')
    String localKey = getDataValue('local_key')
    SynchronousQueue queue = getQ()
    String ip = getIPFromInt(data.startIp)
    LOG.info "scanning for tuya device at ${ip}"
    try {
        tuyaSendCommand(id, ip, localKey, [(powerDps as String): true], 'CONTROL')
        if (queue.poll(250, TimeUnit.MILLISECONDS)?.commandByte == 7 ) {
            LOG.info "found Tuya device at ${ip}"
            device.updateSetting('ipAddress', [value: ip, type: 'text'] )
            updated()
            return
        }
    } catch (ConnectException) { }

    if (data.startIp < data.endIp) {
        data.startIp++
        runInMillis(0, 'scanNetworkAction', [data: data])
    } else {
        log.info 'completed network scanning, device not found'
    }
}

// Component command to set color
void setColor(Map colorMap) {
    LOG.info "setting color to ${colorMap}"

    Map functions = getFunctions(device)
    String code = getFunctionByCode(functions, tuyaFunctions.colour)
    Map color = functions[code] ?: defaults[code]
    Map bright = functions['bright_value'] ?: functions['bright_value_v2'] ?: color.v
    int h = remap(colorMap.hue, 0, 100, color.h.min, color.h.max)
    int s = remap(colorMap.saturation, 0, 100, color.s.min, color.s.max)
    int v = remap(colorMap.level, 0, 100, bright.min, bright.max)
    def (int r, int g, int b) = ColorUtils.hsvToRGB([colorMap.hue, colorMap.saturation, colorMap.level])
    String colorName = translateColor(colorMap.hue as int, colorMap.saturation as int)
    String rgb = Integer.toHexString(r).padLeft(2, '0') +
                 Integer.toHexString(g).padLeft(2, '0') +
                 Integer.toHexString(b).padLeft(2, '0')
    String hsv = Integer.toHexString(h).padLeft(4, '0') +
                 Integer.toHexString(s).padLeft(2, '0') +
                 Integer.toHexString(v).padLeft(2, '0')

    if (repeatCommand([ '5': rgb + hsv, '2': 'colour' ])) {
        sendEvent([ name: 'hue', value: colorMap.hue, descriptionText: "hue is ${colorMap.hue}" ])
        sendEvent([ name: 'saturation', value: colorMap.saturation, descriptionText: "saturation is ${colorMap.saturation}" ])
        sendEvent([ name: 'level', value: colorMap.level, unit: '%', descriptionText: "level is ${colorMap.level}%" ])
        sendEvent([ name: 'colorName', value: colorName, descriptionText: "color name is ${colorName}" ])
        sendEvent([ name: 'colorMode', value: 'RGB', descriptionText: 'color mode is RGB' ])
    } else {
        parent?.componentSetColor(device, colorMap)
    }
}

// Send custom Dps command
void sendCustomDps(BigDecimal dps, String value) {
    LOG.info "sending DPS ${dps} command ${value}"
    switch (value.toLowerCase()) {
        case 'true':
            repeatCommand([ (dps): true ])
            return
        case 'false':
            repeatCommand([ (dps): false ])
            return
    }

    repeatCommand([ (dps): value ])
}

// Component command to set color temperature
void setColorTemperature(BigDecimal kelvin, BigDecimal level = null, BigDecimal duration = null) {
    LOG.info "setting color temperature to ${kelvin}K"

    Map functions = getFunctions(device)
    String code = getFunctionByCode(functions, tuyaFunctions.temperature)
    Map temp = functions[code] ?: defaults[code]
    Integer value = temp.max - Math.ceil(remap(1000000 / kelvin, minMireds, maxMireds, temp.min, temp.max))

    if (repeatCommand([ '4': value ])) {
        sendEvent([ name: 'colorTemperature', value: kelvin, unit: 'K', descriptionText: "color temperature is ${kelvin}K" ])
        sendEvent([ name: 'colorMode', value: 'CT', descriptionText: 'color mode is CT' ])
    } else {
        parent?.componentSetColorTemperature(device, kelvin, level, duration)
        return
    }

    if (level && device.currentValue('level') != level) {
        setLevel(level, duration)
    }
}

void setEffect(BigDecimal effect) {
    LOG.info "setting effect to ${effect}"
    String value = "scene${effect}"
    if (repeatCommand([ 2: value ])) {
        sendEvent( [ name: 'effectName', value: value, descriptionText: "scene is ${value}" ] )
    }
}

void setNextEffect() {
    String effect = device.currentValue('effectName')
    if (effect) {
        int value = effect[-1] as int
        if (value == settings.fxCount) { value = 0 }
        setEffect(value + 1)
    }
}

void setPreviousEffect() {
    String effect = device.currentValue('effectName')
    if (effect) {
        int value = effect[-1] as int
        if (value == 1) { value = settings.fxCount + 1 }
        setEffect(value - 1)
    }
}

// Component command to set hue
void setHue(BigDecimal hue) {
    setColor([
        hue: hue,
        saturation: device.currentValue('saturation'),
        level: device.currentValue('level') ?: 100
    ])
}

// Component command to set level
void setLevel(BigDecimal level, BigDecimal duration = 0) {
    LOG.info "setting level to ${level}%"

    String colorMode = device.currentValue('colorMode')
    if (colorMode == 'CT') {
        Map functions = getFunctions(device)
        String code = getFunctionByCode(functions, tuyaFunctions.brightness)
        Map bright = functions[code] ?: defaults[code]
        Integer value = Math.ceil(remap(level, 0, 100, bright.min, bright.max))
        if (repeatCommand([ '3': value ])) {
            sendEvent([ name: 'level', value: level, unit: '%', descriptionText: "level is ${level}%" ])
        } else {
            parent?.componentSetLevel(device, level, duration)
        }
    } else {
        setColor([
            hue: device.currentValue('hue'),
            saturation: device.currentValue('saturation'),
            level: level
        ])
    }
}

// Component command to set saturation
void setSaturation(BigDecimal saturation) {
    setColor([
        hue: device.currentValue('hue') ?: 100,
        saturation: saturation,
        level: device.currentValue('level') ?: 100
    ])
}

// Socket status updates
void socketStatus(String message) {
    if (message.contains('error')) {
        LOG.error "socket ${message}"
        int val = (device.currentValue('errors') ?: 0) as int
        sendEvent ([ name: 'errors', value: val + 1, descriptionText: message ])
    } else {
        LOG.info "socket ${message}"
    }
}

// Called when the device is removed
void uninstalled() {
    LOG.info "driver uninstalled"
}

// Called when the settings are updated
void updated() {
    LOG.info "driver configuration updated"
    LOG.debug settings
    initialize()
    if (logEnable) { runIn(1800, 'logsOff') }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    LOG.warn 'debug logging disabled'
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
}

#include tuya.tuyaProtocols

private static String getIPFromInt(long ipaslong) {
    return String.format("%d.%d.%d.%d",
        (ipaslong >>> 24) & 0xff,
        (ipaslong >>> 16) & 0xff,
        (ipaslong >>>  8) & 0xff,
        (ipaslong       ) & 0xff)
}

public static long ipToLong(String ipAddress) {
    long result = 0
    String[] ipAddressInArray = ipAddress.split('\\.')
    for (int i = 3; i >= 0; i--) {
        long ip = Long.parseLong(ipAddressInArray[3 - i])
        result |= ip << (i * 8)
    }

    return result;
}

private static Map getFunctions(DeviceWrapper device) {
    return new JsonSlurper().parseText(device.getDataValue('functions'))
}

private static String getFunctionByCode(Map functions, List codes) {
    return codes.find { c -> tuyaFunctions.containsKey(c) } ?: codes.first()
}

private static BigDecimal remap(BigDecimal oldValue, BigDecimal oldMin, BigDecimal oldMax,
                                BigDecimal newMin, BigDecimal newMax) {
    BigDecimal value = oldValue
    if (value < oldMin) { value = oldMin }
    if (value > oldMax) { value = oldMax }
    BigDecimal newValue = ( (value - oldMin) / (oldMax - oldMin) ) * (newMax - newMin) + newMin
    return newValue.setScale(1, BigDecimal.ROUND_HALF_UP)
}

private static String translateColor(Integer hue, Integer saturation) {
    if (saturation < 1) {
        return 'White'
    }

    switch (hue * 3.6 as int) {
        case 0..15: return 'Red'
        case 16..45: return 'Orange'
        case 46..75: return 'Yellow'
        case 76..105: return 'Chartreuse'
        case 106..135: return 'Green'
        case 136..165: return 'Spring'
        case 166..195: return 'Cyan'
        case 196..225: return 'Azure'
        case 226..255: return 'Blue'
        case 256..285: return 'Violet'
        case 286..315: return 'Magenta'
        case 316..345: return 'Rose'
        case 346..360: return 'Red'
    }

    return ''
}

private SynchronousQueue getQ() {
    return queues.computeIfAbsent(device.id) { k -> new SynchronousQueue() };
}

private void parseDeviceState(Map dps) {
    LOG.debug "parsing dps ${dps}"
    Map functions = getFunctions(device)

    String colorMode = device.currentValue('colorMode')
    List<Map> events = []

    if (dps.containsKey(powerDps as String)) {
        String value = dps[powerDps as String] ? 'on' : 'off'
        events << [ name: 'switch', value: value, descriptionText: "switch is ${value}" ]
    }

    // Determine if we are in RGB or CT mode either explicitly or implicitly
    if (dps.containsKey('2') && dps['2'].startsWith('scene')) {
        String effect = dps['2']
        events << [ name: 'effectName', value: effect, descriptionText: "scene is ${effect}" ]
    } else if (dps.containsKey('2')) {
        colorMode = dps['2'] == 'colour' ? 'RGB' : 'CT'
        events << [ name: 'colorMode', value: colorMode, descriptionText: "color mode is ${colorMode}" ]
        events << [ name: 'effectName', value: '' ]
    } else if (dps.containsKey('4')) {
        colorMode = 'CT'
        events << [ name: 'colorMode', value: colorMode, descriptionText: "color mode is ${colorMode}" ]
    } else if (dps.containsKey('5')) {
        colorMode = 'RGB'
        events << [ name: 'colorMode', value: colorMode, descriptionText: "color mode is ${colorMode}" ]
    }

    if (dps.containsKey('3') && colorMode == 'CT') {
        Map code = functions[getFunctionByCode(functions, tuyaFunctions.brightness)]
        Integer value = Math.floor(remap(dps['3'] as int, code.min, code.max, 0, 100))
        events << [ name: 'level', value: value, unit: '%', descriptionText: "level is ${value}%" ]
    }

    if (dps.containsKey('4')) {
        Map code = functions[getFunctionByCode(functions, tuyaFunctions.temperature)]
        Integer value = Math.floor(1000000 / remap(code.max - dps['4'] as int,
                                   code.min, code.max, minMireds, maxMireds))
        events << [ name: 'colorTemperature', value: value, unit: 'K',
                    descriptionText: "color temperature is ${value}K" ]
    }

    if (dps.containsKey('5') && colorMode == 'RGB') {
        String value = dps['5']
        // first six characters are RGB values which we ignore and use HSV instead
        Matcher match = value =~ /^.{6}([0-9a-f]{4})([0-9a-f]{2})([0-9a-f]{2})$/
        if (match.find()) {
            Map code = functions[getFunctionByCode(functions, tuyaFunctions.colour)]
            Map bright = functions[getFunctionByCode(functions, tuyaFunctions.brightness)]
            int h = Integer.parseInt(match.group(1), 16)
            int s = Integer.parseInt(match.group(2), 16)
            int v = Integer.parseInt(match.group(3), 16)
            Integer hue = Math.floor(remap(h, code.h.min, code.h.max, 0, 100))
            Integer saturation = Math.floor(remap(s, code.s.min, code.s.max, 0, 100))
            Integer level = Math.floor(remap(v, bright.min, bright.max, 0, 100))
            String colorName = translateColor(hue, saturation)
            events << [ name: 'hue', value: hue, descriptionText: "hue is ${hue}" ]
            events << [ name: 'colorName', value: colorName, descriptionText: "color name is ${colorName}" ]
            events << [ name: 'saturation', value: saturation, descriptionText: "saturation is ${saturation}" ]
            events << [ name: 'level', value: level, unit: '%', descriptionText: "level is ${level}%" ]
        }
    }

    events.each { e ->
        if (device.currentValue(e.name) != e.value) {
            if (e.descriptionText && txtEnable) { LOG.info "${e.descriptionText}" }
            sendEvent(e)
        }
    }
}

private void increaseErrorCount(msg = '') {
    int val = (device.currentValue('errors') ?: 0) as int
    sendEvent ([ name: 'errors', value: val + 1, descriptionText: msg ])
}

private void increaseRetryCount(msg = '') {
    int val = (device.currentValue('retries') ?: 0) as int
    sendEvent ([ name: 'retries', value: val + 1,, descriptionText: msg ])
}

private Map repeatCommand(Map dps) {
    Map result
    String id = getDataValue('id')
    String localKey = getDataValue('local_key')
    SynchronousQueue queue = getQ()
    if (!id || !localKey || !ipAddress) { return result }

    // Synchronized required to stop multiple commands being sent
    // simultaneously which will overload the tuya TCP stack
    synchronized(queue) {
        for (i = 1; i <= repeat; i++) {
            try {
                LOG.debug "sending DPS command ${dps}"
                tuyaSendCommand(id, ipAddress, localKey, dps, 'CONTROL')
            } catch (e) {
                LOG.exception "tuya send exception", e
                increaseErrorCount(e.message)
                pauseExecution(250)
                increaseRetryCount()
                continue
            }

            result = queue.poll(timeoutSecs, TimeUnit.SECONDS)
            if (result) {
                LOG.info "received device ack"
                break
            } else {
                LOG.warn "command timeout (${i} of ${repeat})"
                increaseRetryCount()
            }
        }
    }

    return result
}

@Field private final LOG = [
    debug: { s -> if (settings.logEnable == true) { log.debug(s) } },
    info: { s -> log.info(s) },
    warn: { s -> log.warn(s) },
    error: { s -> log.error(s) },
    exception: { message, exception ->
        List<StackTraceElement> relevantEntries = exception.stackTrace.findAll { entry -> entry.className.startsWith("user_app") }
        Integer line = relevantEntries[0]?.lineNumber
        String method = relevantEntries[0]?.methodName
        log.error("${message}: ${exception} at line ${line} (${method})")
        if (settings.logEnable) {
            log.debug("App exception stack trace:\n${relevantEntries.join("\n")}")
        }
    }
]
