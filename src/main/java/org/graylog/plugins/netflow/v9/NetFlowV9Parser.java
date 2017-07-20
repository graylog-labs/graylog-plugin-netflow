/*
 * Copyright 2013 Eediom Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.graylog.plugins.netflow.v9;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.netty.buffer.ByteBuf;
import org.graylog.plugins.netflow.flows.InvalidFlowVersionException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;


public class NetFlowV9Parser {
    private static AtomicReference<NetFlowV9OptionTemplate> optionTemplateReference = new AtomicReference<>();

    public static NetFlowV9Packet parsePacket(ByteBuf bb, NetFlowV9TemplateCache cache, NetFlowV9FieldTypeRegistry typeRegistry) {
        final int dataLength = bb.readableBytes();
        final NetFlowV9Header header = parseHeader(bb);

        List<NetFlowV9Template> templates = Collections.emptyList();
        NetFlowV9OptionTemplate optTemplate = null;
        List<NetFlowV9BaseRecord> records = Collections.emptyList();
        while (bb.isReadable()) {
            bb.markReaderIndex();
            int flowSetId = bb.readUnsignedShort();
            if (flowSetId == 0) {
                templates = parseTemplates(bb, typeRegistry);
                for (NetFlowV9Template t : templates) {
                    cache.put(t.templateId(), t);
                }
            } else if (flowSetId == 1) {
                optTemplate = parseOptionTemplate(bb, typeRegistry);
                optionTemplateReference.set(optTemplate);
            } else {
                bb.resetReaderIndex();
                records = parseRecords(bb, cache);
            }
        }

        return NetFlowV9Packet.create(
                header,
                templates,
                optTemplate,
                records,
                dataLength);
    }

    /**
     * Flow Header Format
     *
     * <pre>
     * |  0-1  |     version      |                                                                                                                                                                                                                                                                                                                                                                                              NetFlow export format version number                                                                                                                                                                                                                                                                                                                                                                                               |
     * |-------|------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
     * | 2-3   | count            | Number of flow sets exported in this packet, both template and data (1-30).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
     * | 4-7   | sys_uptime       | Current time in milliseconds since the export device booted.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
     * | 8-11  | unix_secs        | Current count of seconds since 0000 UTC 1970.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
     * | 12-15 | package_sequence | Sequence counter of all export packets sent by the export device. Note: This is a change from the Version 5 and Version 8 headers, where this number represented total flows.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
     * | 16-19 | source_id        | A 32-bit value that is used to guarantee uniqueness for all flows exported from a particular device. (The Source ID field is the equivalent of the engine type and engine ID fields found in the NetFlow Version 5 and Version 8 headers). The format of this field is vendor specific. In Cisco's implementation, the first two bytes are reserved for future expansion, and will always be zero. Byte 3 provides uniqueness with respect to the routing engine on the exporting device. Byte 4 provides uniqueness with respect to the particular line card or Versatile Interface Processor on the exporting device. Collector devices should use the combination of the source IP address plus the Source ID field to associate an incoming NetFlow export packet with a unique instance of NetFlow on a particular device. |
     * </pre>
     */
    public static NetFlowV9Header parseHeader(ByteBuf bb) {
        final int version = bb.readUnsignedShort();
        if (version != 9) {
            throw new InvalidFlowVersionException(version);
        }

        final int count = bb.readUnsignedShort();
        final long sysUptime = bb.readUnsignedInt();
        final long unixSecs = bb.readUnsignedInt();
        final long sequence = bb.readUnsignedInt();
        final long sourceId = bb.readUnsignedInt();

        return NetFlowV9Header.create(version, count, sysUptime, unixSecs, sequence, sourceId);
    }

    /**
     * Template FlowSet Format
     *
     * <pre>
     * |    FIELD     |                                                                                                                                                                                                                                                                DESCRIPTION                                                                                                                                                                                                                                                                |
     * |--------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
     * | flowset_id   | The flowset_id is used to distinguish template records from data records. A template record always has a flowset_id in the range of 0-255. Currently template record that describes flow fields has a flowset_id of zero and the template record that describes option fields (described below) has a flowset_id of 1. A data record always has a nonzero flowset_id greater than 255.                                                                                                                                                    |
     * | length       | Length refers to the total length of this FlowSet. Because an individual template FlowSet may contain multiple template IDs (as illustrated above), the length value should be used to determine the position of the next FlowSet record, which could be either a template or a data FlowSet. Length is expressed in type/length/value (TLV) format, meaning that the value includes the bytes used for the flowset_id and the length bytes themselves, as well as the combined lengths of all template records included in this FlowSet. |
     * | template_id  | As a router generates different template FlowSets to match the type of NetFlow data it will be exporting, each template is given a unique ID. This uniqueness is local to the router that generated the template_id. Templates that define data record formats begin numbering at 256 since 0-255 are reserved for FlowSet IDs.                                                                                                                                                                                                           |
     * | field_count  | This field gives the number of fields in this template record. Because a template FlowSet may contain multiple template records, this field allows the parser to determine the end of the current template record and the start of the next.                                                                                                                                                                                                                                                                                              |
     * | field_type   | This numeric value represents the type of the field. The possible values of the field type are vendor specific. Cisco supplied values are consistent across all platforms that support NetFlow Version 9. At the time of the initial release of the NetFlow Version 9 code (and after any subsequent changes that could add new field-type definitions), Cisco provides a file that defines the known field types and their lengths. The currently defined field types are detailed below.                                                |
     * | field_length | This number gives the length of the above-defined field, in bytes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
     * </pre>
     */
    public static List<NetFlowV9Template> parseTemplates(ByteBuf bb, NetFlowV9FieldTypeRegistry typeRegistry) {
        final ImmutableList.Builder<NetFlowV9Template> templates = ImmutableList.builder();
        int len = bb.readUnsignedShort();

        int p = 4; // flow set id and length field itself
        while (p < len) {
            final int templateId = bb.readUnsignedShort();
            final int fieldCount = bb.readUnsignedShort();
            final ImmutableList.Builder<NetFlowV9FieldDef> fieldDefs = ImmutableList.builder();
            for (int i = 0; i < fieldCount; i++) {
                int fieldType = bb.readUnsignedShort();
                int fieldLength = bb.readUnsignedShort();
                final NetFlowV9FieldType type = typeRegistry.get(fieldType);
                final NetFlowV9FieldDef fieldDef = NetFlowV9FieldDef.create(type, fieldLength);
                fieldDefs.add(fieldDef);
            }

            final NetFlowV9Template template = NetFlowV9Template.create(templateId, fieldCount, fieldDefs.build());
            templates.add(template);

            p += 4 + template.fieldCount() * 4;
        }

        return templates.build();
    }

    /**
     * Options Template Format
     *
     * <pre>
     * |         FIELD         |                                                                                                                                                                                                                                          DESCRIPTION                                                                                                                                                                                                                                           |
     * |-----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
     * | flowset_id = 1        | The flowset_id is used to distinguish template records from data records. A template record always has a flowset_id of 1. A data record always has a nonzero flowset_id which is greater than 255.                                                                                                                                                                                                                                                                                             |
     * | length                | This field gives the total length of this FlowSet. Because an individual template FlowSet may contain multiple template IDs, the length value should be used to determine the position of the next FlowSet record, which could be either a template or a data FlowSet. Length is expressed in TLV format, meaning that the value includes the bytes used for the flowset_id and the length bytes themselves, as well as the combined lengths of all template records included in this FlowSet. |
     * | template_id           | As a router generates different template FlowSets to match the type of NetFlow data it will be exporting, each template is given a unique ID. This uniqueness is local to the router that generated the template_id. The template_id is greater than 255. Template IDs inferior to 255 are reserved.                                                                                                                                                                                           |
     * | option_scope_length   | This field gives the length in bytes of any scope fields contained in this options template (the use of scope is described below).                                                                                                                                                                                                                                                                                                                                                             |
     * | options_length        | This field gives the length (in bytes) of any Options field definitions contained in this options template.                                                                                                                                                                                                                                                                                                                                                                                    |
     * | scope_field_N_type    | This field gives the relevant portion of the NetFlow process to which the options record refers. Currently defined values follow:                                                                                                                                                                                                                                                                                                                                                              |
     * |                       | * 0x0001 System                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
     * |                       | * 0x0002 Interface                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
     * |                       | * 0x0003 Line Card                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
     * |                       | * 0x0004 NetFlow Cache                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
     * |                       | * 0x0005 Template                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
     * |                       |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
     * |                       | For example, sampled NetFlow can be implemented on a per-interface basis, so if the options record were reporting on how sampling is configured, the scope for the report would be 0x0002 (interface).                                                                                                                                                                                                                                                                                         |
     * | scope_field_N_length  | This field gives the length (in bytes) of the Scope field, as it would appear in an options record.                                                                                                                                                                                                                                                                                                                                                                                            |
     * | option_field_N_type   | This numeric value represents the type of the field that appears in the options record. Possible values are detailed in template flow set format (above).                                                                                                                                                                                                                                                                                                                                      |
     * | option_field_N_length | This number is the length (in bytes) of the field, as it would appear in an options record.                                                                                                                                                                                                                                                                                                                                                                                                    |
     * | padding               | Padding should be inserted to align the end of the FlowSet on a 32 bit boundary. Pay attention that the length field will include those padding bits.                                                                                                                                                                                                                                                                                                                                          |
     * </pre>
     */
    public static NetFlowV9OptionTemplate parseOptionTemplate(ByteBuf bb, NetFlowV9FieldTypeRegistry typeRegistry) {
        int length = bb.readUnsignedShort();
        final int templateId = bb.readUnsignedShort();

        int optionScopeLength = bb.readUnsignedShort();
        int optionLength = bb.readUnsignedShort();

        int p = bb.readerIndex();
        int endOfScope = p + optionScopeLength;
        int endOfOption = endOfScope + optionLength;
        int endOfTemplate = p - 10 + length;

        final ImmutableList.Builder<NetFlowV9ScopeDef> scopeDefs = ImmutableList.builder();
        while (bb.readerIndex() < endOfScope) {
            int scopeType = bb.readUnsignedShort();
            int scopeLength = bb.readUnsignedShort();
            scopeDefs.add(NetFlowV9ScopeDef.create(scopeType, scopeLength));
        }

        // skip padding
        bb.readerIndex(endOfScope);

        final ImmutableList.Builder<NetFlowV9FieldDef> optionDefs = ImmutableList.builder();
        while (bb.readerIndex() < endOfOption) {
            int optType = bb.readUnsignedShort();
            int optLength = bb.readUnsignedShort();
            NetFlowV9FieldType t = typeRegistry.get(optType);
            optionDefs.add(NetFlowV9FieldDef.create(t, optLength));
        }

        // skip padding
        bb.readerIndex(endOfTemplate);

        return NetFlowV9OptionTemplate.create(templateId, scopeDefs.build(), optionDefs.build());
    }

    /**
     * Data FlowSet Format
     *
     * <pre>
     * |      FIELD       |                                                                                                                                        DESCRIPTION                                                                                                                                        |
     * |------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
     * | flowset_id       | A FlowSet ID precedes each group of records within a NetFlow Version 9 data FlowSet. The FlowSet ID maps to a (previously received) template_id. The collector and display applications should use the flowset_id to map the appropriate type and length to any field values that follow. |
     * | length           | This field gives the length of the data FlowSet. Length is expressed in TLV format, meaning that the value includes the bytes used for the flowset_id and the length bytes themselves, as well as the combined lengths of any included data records.                                      |
     * | record_N—field_M | The remainder of the Version 9 data FlowSet is a collection of field values. The type and length of the fields have been previously defined in the template record referenced by the flowset_id/template_id.                                                                              |
     * | padding          | Padding should be inserted to align the end of the FlowSet on a 32 bit boundary. Pay attention that the length field will include those padding bits.                                                                                                                                     |
     * </pre>
     */
    public static List<NetFlowV9BaseRecord> parseRecords(ByteBuf bb, NetFlowV9TemplateCache cache) {
        List<NetFlowV9BaseRecord> records = new ArrayList<>();
        int flowSetId = bb.readUnsignedShort();
        int length = bb.readUnsignedShort();
        int end = bb.readerIndex() - 4 + length;

        List<NetFlowV9FieldDef> defs = null;

        final NetFlowV9OptionTemplate optionTemplate = optionTemplateReference.get();
        boolean isOptionTemplate = optionTemplate != null && optionTemplate.templateId() == flowSetId;
        if (isOptionTemplate) {
            defs = optionTemplate.optionDefs();
        } else {
            NetFlowV9Template t = cache.get(flowSetId);
            if (t == null) {
                return Collections.emptyList();
            }
            defs = t.definitions();
        }

        // calculate record unit size
        int unitSize = 0;
        for (NetFlowV9FieldDef def : defs) {
            unitSize += def.length();
        }

        while (bb.readerIndex() < end && bb.readableBytes() >= unitSize) {
            final ImmutableMap.Builder<String, Object> fields = ImmutableMap.builder();
            for (NetFlowV9FieldDef def : defs) {
                final String key = def.type().name().toLowerCase();
                final Optional<Object> optValue = def.parse(bb);
                optValue.ifPresent(value -> fields.put(key, value));
            }

            if (isOptionTemplate) {
                final ImmutableMap.Builder<Integer, Object> scopes = ImmutableMap.builder();
                for (NetFlowV9ScopeDef def : optionTemplate.scopeDefs()) {
                    int t = def.type();
                    int len = def.length();

                    long l = 0;
                    for (int i = 0; i < len; i++) {
                        l <<= 8;
                        l |= bb.readUnsignedByte();
                    }

                    scopes.put(t, l);
                }

                records.add(NetFlowV9OptionRecord.create(fields.build(), scopes.build()));
            } else {
                records.add(NetFlowV9Record.create(fields.build()));
            }
        }

        bb.readerIndex(end);
        return records;
    }
}
