/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.keyvalue.core.AbstractKeyValueAdapter;
import org.springframework.data.keyvalue.core.KeyValueAdapter;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.convert.MappingRedisConverter;
import org.springframework.data.redis.core.convert.RedisConverter;
import org.springframework.data.redis.core.convert.RedisData;
import org.springframework.data.redis.core.convert.ReferenceResolverImpl;
import org.springframework.data.redis.core.index.IndexConfiguration;
import org.springframework.data.redis.util.ByteUtils;
import org.springframework.data.util.CloseableIterator;

/**
 * Redis specific {@link KeyValueAdapter} implementation. Uses binary codec to read/write data from/to Redis.
 * 
 * @author Christoph Strobl
 */
public class RedisKeyValueAdapter extends AbstractKeyValueAdapter {

	private RedisOperations<?, ?> redisOps;

	private MappingRedisConverter converter;

	public RedisKeyValueAdapter() {
		this((IndexConfiguration) null);
	}

	public RedisKeyValueAdapter(IndexConfiguration indexConfiguration) {

		super(new RedisQueryEngine());

		converter = new MappingRedisConverter(indexConfiguration, new ReferenceResolverImpl(this));

		JedisConnectionFactory conFactory = new JedisConnectionFactory();
		conFactory.afterPropertiesSet();

		RedisTemplate<byte[], byte[]> template = new RedisTemplate<byte[], byte[]>();
		template.setConnectionFactory(conFactory);
		template.afterPropertiesSet();

		this.redisOps = template;
	}

	/**
	 * @param redisOps
	 * @param indexConfiguration
	 */
	public RedisKeyValueAdapter(RedisOperations<?, ?> redisOps, IndexConfiguration indexConfiguration) {

		super(new RedisQueryEngine());

		converter = new MappingRedisConverter(indexConfiguration, new ReferenceResolverImpl(this));
		this.redisOps = redisOps;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#put(java.io.Serializable, java.lang.Object, java.io.Serializable)
	 */
	public Object put(final Serializable id, final Object item, final Serializable keyspace) {

		final RedisData rdo = new RedisData();
		converter.write(item, rdo);

		final byte[] indexPostFixPattern = converter.toBytes(":*");

		redisOps.execute(new RedisCallback<Object>() {

			@Override
			public Object doInRedis(RedisConnection connection) throws DataAccessException {

				connection.hMSet(rdo.getKey(), rdo.getData());
				connection.sAdd(rdo.getKeyspace(), rdo.getId());

				// remove id from potential indexes since those might be invalid with the new data
				for (byte[] potentialIndex : rdo.getIndexPaths()) {

					Set<byte[]> existingKeys = connection.keys(ByteUtils.concat(potentialIndex, indexPostFixPattern));

					for (byte[] existingKey : existingKeys) {
						connection.sRem(existingKey, rdo.getId());
					}
				}

				if (!rdo.getSimpleIndexKeys().isEmpty()) {

					for (byte[] index : rdo.getSimpleIndexKeys()) {
						connection.sAdd(index, rdo.getId());
					}
				}
				return null;
			}
		});

		return item;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#contains(java.io.Serializable, java.io.Serializable)
	 */
	public boolean contains(final Serializable id, final Serializable keyspace) {

		Boolean exists = redisOps.execute(new RedisCallback<Boolean>() {

			@Override
			public Boolean doInRedis(RedisConnection connection) throws DataAccessException {
				return connection.sIsMember(converter.toBytes(keyspace), converter.toBytes(id));
			}
		});

		return exists != null ? exists.booleanValue() : false;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#get(java.io.Serializable, java.io.Serializable)
	 */
	public Object get(Serializable id, Serializable keyspace) {

		final byte[] binId = converter.convertToId(keyspace, id);

		Map<byte[], byte[]> raw = redisOps.execute(new RedisCallback<Map<byte[], byte[]>>() {

			@Override
			public Map<byte[], byte[]> doInRedis(RedisConnection connection) throws DataAccessException {
				return connection.hGetAll(binId);
			}
		});

		return converter.read(Object.class, new RedisData(raw));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#delete(java.io.Serializable, java.io.Serializable)
	 */
	public Object delete(final Serializable id, final Serializable keyspace) {

		final byte[] binId = converter.toBytes(id);
		final byte[] binKeyspace = converter.toBytes(keyspace);

		Object o = get(id, keyspace);

		if (o != null) {

			redisOps.execute(new RedisCallback<Void>() {

				@Override
				public Void doInRedis(RedisConnection connection) throws DataAccessException {

					connection.del(converter.convertToId(binKeyspace, binId));
					connection.sRem(binKeyspace, binId);

					Set<byte[]> potentialIndex = connection.keys(converter.toBytes(keyspace + ".*"));

					for (byte[] indexKey : potentialIndex) {
						try {
							connection.sRem(indexKey, binId);
						} catch (Exception e) {
							System.err.println(e);
						}
					}
					return null;
				}
			});

		}
		return o;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#getAllOf(java.io.Serializable)
	 */
	public List<?> getAllOf(final Serializable keyspace) {

		final byte[] binKeyspace = converter.toBytes(keyspace);

		List<Map<byte[], byte[]>> raw = redisOps.execute(new RedisCallback<List<Map<byte[], byte[]>>>() {

			@Override
			public List<Map<byte[], byte[]>> doInRedis(RedisConnection connection) throws DataAccessException {

				final List<Map<byte[], byte[]>> rawData = new ArrayList<Map<byte[], byte[]>>();

				Set<byte[]> members = connection.sMembers(binKeyspace);

				for (byte[] id : members) {
					rawData.add(connection.hGetAll(converter.convertToId(binKeyspace, id)));
				}

				return rawData;
			}
		});

		List<Object> result = new ArrayList<Object>(raw.size());
		for (Map<byte[], byte[]> rawData : raw) {
			result.add(converter.read(Object.class, new RedisData(rawData)));
		}

		return result;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#deleteAllOf(java.io.Serializable)
	 */
	public void deleteAllOf(final Serializable keyspace) {

		redisOps.execute(new RedisCallback<Void>() {

			@Override
			public Void doInRedis(RedisConnection connection) throws DataAccessException {

				connection.del(converter.toBytes(keyspace));

				Set<byte[]> potentialIndex = connection.keys(converter.toBytes(keyspace + ".*"));

				for (byte[] indexKey : potentialIndex) {
					connection.del(indexKey);
				}
				return null;
			}
		});
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#entries(java.io.Serializable)
	 */
	public CloseableIterator<Entry<Serializable, Object>> entries(Serializable keyspace) {
		throw new UnsupportedOperationException("Not yet implemented");
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.keyvalue.core.KeyValueAdapter#count(java.io.Serializable)
	 */
	public long count(final Serializable keyspace) {

		Long count = redisOps.execute(new RedisCallback<Long>() {

			@Override
			public Long doInRedis(RedisConnection connection) throws DataAccessException {
				return connection.sCard(converter.toBytes(keyspace));
			}
		});

		return count != null ? count.longValue() : 0;
	}

	/**
	 * Execute {@link RedisCallback} via underlying {@link RedisOperations}.
	 * 
	 * @param callback must not be {@literal null}.
	 * @see RedisOperations#execute(RedisCallback)
	 * @return
	 */
	public <T> T execute(RedisCallback<T> callback) {
		return redisOps.execute(callback);
	}

	/**
	 * Get the {@link RedisConverter} in use.
	 * 
	 * @return never {@literal null}.
	 */
	public RedisConverter getConverter() {
		return this.converter;
	}

	public void clear() {
		// nothing to do
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.beans.factory.DisposableBean#destroy()
	 */
	public void destroy() throws Exception {

		if (redisOps instanceof RedisTemplate) {
			RedisConnectionFactory connectionFactory = ((RedisTemplate<?, ?>) redisOps).getConnectionFactory();
			if (connectionFactory instanceof DisposableBean) {
				((DisposableBean) connectionFactory).destroy();
			}
		}
	}

}
