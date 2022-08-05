package de.mischmaschine.database.redis

import de.mischmaschine.database.database.Configuration
import de.mischmaschine.database.database.Database
import org.redisson.Redisson
import org.redisson.api.RFuture
import org.redisson.api.RedissonClient
import org.redisson.codec.JsonJacksonCodec
import org.redisson.codec.TypedJsonJacksonCodec
import org.redisson.config.Config
import java.util.logging.Level

/**
 * ## AbstractRedis
 * This class is the base class for all Redis implementations.
 * It provides the basic functionality to connect to a Redis server and to execute commands/listen to channels (pubSub).
 * It is not intended to be used directly, but rather as a base class for concrete implementations.
 */
abstract class AbstractRedis(database: Int, logging: Boolean, ssl: Boolean) : Database {

    private val redissonClient: RedissonClient

    init {

        val host = Configuration.getHost(AbstractRedis::class)
        val port = Configuration.getPort(AbstractRedis::class)
        val password = Configuration.getPassword(AbstractRedis::class)

        if (host.isEmpty()) throw IllegalArgumentException("No host specified for Redis database $database")
        if (port == 0) throw IllegalArgumentException("No port specified for Redis database $database")
        Config().also {
            it.useSingleServer().apply {
                this.address = if (ssl) "rediss://$host:$port" else "redis://$host:$port"
                this.password = password
                this.database = database
            }
        }.also {
            this.redissonClient = Redisson.create(it)
        }

        if (!logging) this.logger.level = Level.OFF

    }

    /**
     * Updates the value of the given key asynchronously.
     *
     * @param key The key to update.
     * @param data The data to update the key with.
     */
    inline fun <reified T : Any> updateKeyAsync(key: String, data: T) {
        getRedissonClient().getBucket<T>(key, TypedJsonJacksonCodec(T::class.java)).setAsync(data)
    }

    /**
     * Updates the value of the given key synchronously.
     *
     * @param key The key to update.
     * @param data The data to update the key with.
     */
    inline fun <reified T> updateKeySync(key: String, data: T) {
        getRedissonClient().getBucket<T>(key, TypedJsonJacksonCodec(T::class.java)).set(data)
    }

    /**
     * Gets the value of the given key synchronously.
     *
     * @param key The key to get the value of.
     *
     * @return the value of the given key, or null if the key does not exist.
     */
    inline fun <reified T> getValueSync(key: String): T? =
        getRedissonClient().getBucket<T>(key, TypedJsonJacksonCodec(T::class.java)).get()

    /**
     * Gets the value of the given key asynchronously.
     *
     * @param key The key to get the value of.
     *
     * @return the value of the given key, or null if the key does not exist.
     */
    inline fun <reified T> getValueAsync(key: String): RFuture<T?> =
        getRedissonClient().getBucket<T>(key, TypedJsonJacksonCodec(T::class.java)).async

    /**
     * Deletes the given key synchronously.
     * @param key The key to delete.
     * @see [redisSync]
     */
    inline fun <reified T> deleteKeySync(vararg key: String) {
        key.forEach {
            getRedissonClient().getBucket<T>(it, TypedJsonJacksonCodec(T::class.java)).delete()
        }
    }

    /**
     * Deletes the given key asynchronously.
     * @param key The key to delete.
     *
     * @see [redisAsync]
     */
    inline fun <reified T> deleteKeyAsync(vararg key: String) {
        key.forEach {
            getRedissonClient().getBucket<T>(it, TypedJsonJacksonCodec(T::class.java)).deleteAsync()
        }
    }

    /**
     * Subscribes to the given channel. The given function will be called when a message is received.
     *
     * @param channel The channel to subscribe to.
     * @param function The function to call when a message is received.
     */
    fun subscribe(channel: String, type: Class<*>, function: (String, Any) -> Unit) {
        Listener(channel, type, function)
    }

    /**
     * Unsubscribes the given channel.
     *
     * @param channel The channel to unsubscribe from.
     */
    fun unsubscribe(vararg channel: String) {
        channel.forEach {
            getRedissonClient().getTopic(it, JsonJacksonCodec()).removeAllListeners()
        }
        logger.info("Unsubscribed from ${channel.joinToString(", ")}")
    }

    /**
     * This method publishes the given message to the given channel.
     *
     * @param channel The channel to publish to.
     * @param message The message to publish.
     */
    inline fun <reified T> publish(channel: String, message: T) {
        getRedissonClient().getTopic(channel, TypedJsonJacksonCodec(T::class.java)).publishAsync(message)
        logger.info("Published to channel '$channel': '$message'")
    }

    fun getRedissonClient() = this.redissonClient

    private inner class Listener(channel: String, type: Class<*>, function: (String, Any) -> Unit) {
        init {
            this@AbstractRedis.redissonClient.getTopic(channel, TypedJsonJacksonCodec(type))
                .addListenerAsync(type) { _, message ->
                    function.invoke(channel, message)
                }
        }
    }
}
