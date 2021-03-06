---
title: "向量化自定义函数"
nav-id: vectorized_python_udf
nav-parent_id: python_udf
nav-pos: 30
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

向量化Python用户自定义函数，是在执行时，通过在JVM和Python VM之间以Arrow列存格式批量传输数据，来执行的函数。
向量化Python用户自定义函数的性能通常比非向量化Python用户自定义函数要高得多，因为向量化Python用户自定义函数可以大大减少序列化/反序列化的开销和调用开销。
此外，用户可以利用流行的Python库（例如Pandas，Numpy等）来实现向量化Python用户自定义函数的逻辑。这些Python库通常经过高度优化，并提供了高性能的数据结构和功能。
向量化用户自定义函数的定义，与[非向量化用户自定义函数]({% link dev/python/table-api-users-guide/udfs/python_udfs.zh.md %})具有相似的方式，
用户只需要在调用`udf`或者`udaf`装饰器时添加一个额外的参数`func_type="pandas"`，将其标记为一个向量化用户自定义函数即可。

**注意：**要执行Python UDF，需要安装PyFlink的Python版本（3.5、3.6、3.7 或 3.8）。客户端和群集端都需要安装它。

* This will be replaced by the TOC
{:toc}

## 向量化标量函数

向量化Python标量函数以`pandas.Series`类型的参数作为输入，并返回与输入长度相同的`pandas.Series`。
在内部实现中，Flink会将输入数据拆分为多个批次，并将每一批次的输入数据转换为`Pandas.Series`类型，
然后为每一批输入数据调用用户自定义的向量化Python标量函数。请参阅配置选项
[python.fn-execution.arrow.batch.size，]({% link dev/python/table-api-users-guide/python_config.zh.md %}#python-fn-execution-arrow-batch-size)
以获取有关如何配置批次大小的更多详细信息。

向量化Python标量函数可以在任何可以使用非向量化Python标量函数的地方使用。

以下示例显示了如何定义自己的向量化Python标量函数，该函数计算两列的总和，并在查询中使用它：

{% highlight python %}
@udf(result_type=DataTypes.BIGINT(), func_type="pandas")
def add(i, j):
  return i + j

table_env = BatchTableEnvironment.create(env)

# use the vectorized Python scalar function in Python Table API
my_table.select(add(my_table.bigint, my_table.bigint))

# 在SQL API中使用Python向量化标量函数
table_env.create_temporary_function("add", add)
table_env.sql_query("SELECT add(bigint, bigint) FROM MyTable")
{% endhighlight %}

## 向量化聚合函数

向量化Python聚合函数以一个或多个`pandas.Series`类型的参数作为输入，并返回一个标量值作为输出。

向量化Python聚合函数能够用在`GroupBy Aggregation`（Batch），`GroupBy Window Aggregation`(Batch and Stream) 和 
`Over Window Aggregation`(Batch and Stream bounded over window)。关于聚合的更多使用细节，你可以参考
[相关文档]({% link dev/table/tableApi.zh.md %}?code_tab=python#aggregations).

<span class="label label-info">注意</span> 向量化聚合函数不支持部分聚合，而且一个组或者窗口内的所有数据，在执行的过程中，会被同时加载到内存，所以需要确保所配置的内存大小足够容纳这些数据。

<span class="label label-info">注意</span> 向量化聚合函数只支持运行在Blink Planner上。

以下示例显示了如何定一个自己的向量化聚合函数，该函数计算一列的平均值，并在`GroupBy Aggregation`, `GroupBy Window Aggregation`
and `Over Window Aggregation` 使用它:

{% highlight python %}
@udaf(result_type=DataTypes.FLOAT(), func_type="pandas")
def mean_udaf(v):
    return v.mean()

table_env = BatchTableEnvironment.create(
            environment_settings=EnvironmentSettings.new_instance()
            .in_batch_mode().use_blink_planner().build())

my_table = ...  # type: Table, table schema: [a: String, b: BigInt, c: BigInt]

# 在GroupBy Aggregation中使用向量化聚合函数
my_table.group_by(my_table.a).select(my_table.a, mean_udaf(add(my_table.b)))


# 在GroupBy Window Aggregation中使用向量化聚合函数
tumble_window = Tumble.over(expr.lit(1).hours) \
            .on(expr.col("rowtime")) \
            .alias("w")

my_table.window(tumble_window) \
    .group_by("w") \
    .select("w.start, w.end, mean_udaf(b)")


# 在Over Window Aggregation中使用向量化聚合函数
table_env.create_temporary_function("mean_udaf", mean_udaf)
table_env.sql_query("""
    SELECT a,
        mean_udaf(b)
        over (PARTITION BY a ORDER BY rowtime
        ROWS BETWEEN UNBOUNDED preceding AND UNBOUNDED FOLLOWING)
    FROM MyTable""")

{% endhighlight %}

除了直接定义一个Python函数之外，还支持多种方式来定义向量化Python聚合函数。
以下示例显示了多种定义向量化Python聚合函数的方式。该函数需要两个类型为bigint的参数作为输入参数，并返回它们的最大值的和作为结果。

{% highlight python %}

# 方式一：扩展基类 `AggregateFunction`
class MaxAdd(AggregateFunction):

    def open(self, function_context):
        mg = function_context.get_metric_group()
        self.counter = mg.add_group("key", "value").counter("my_counter")
        self.counter_sum = 0

    def get_value(self, accumulator):
        # counter
        self.counter.inc(10)
        self.counter_sum += 10
        return accumulator[0]

    def create_accumulator(self):
        return []

    def accumulate(self, accumulator, *args):
        result = 0
        for arg in args:
            result += arg.max()
        accumulator.append(result)

max_add = udaf(MaxAdd(), result_type=DataTypes.BIGINT(), func_type="pandas")

# 方式二：普通Python函数
@udaf(result_type=DataTypes.BIGINT(), func_type="pandas")
def max_add(i, j):
  return i.max() + j.max()

# 方式三：lambda函数
max_add = udaf(lambda i, j: i.max() + j.max(), result_type=DataTypes.BIGINT(), func_type="pandas")

# 方式四：callable函数
class CallableMaxAdd(object):
  def __call__(self, i, j):
    return i.max() + j.max()

max_add = udaf(CallableMaxAdd(), result_type=DataTypes.BIGINT(), func_type="pandas")

# 方式五：partial函数
def partial_max_add(i, j, k):
  return i.max() + j.max() + k
  
max_add = udaf(functools.partial(partial_max_add, k=1), result_type=DataTypes.BIGINT(), func_type="pandas")

{% endhighlight %}

