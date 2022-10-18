export const jobListColumns = [
  {
    title: 'Id',
    dataIndex: 'id',
    key: 'name',
    width: 20,
  },
  {
    title: 'Network',
    dataIndex: 'networkId',
    key: 'networkId',
    width: 20,
  },
  {
    title: 'Balance',
    dataIndex: 'price',
    key: 'price',
    width: 20,
  },
  {
    title: 'Address',
    dataIndex: 'tokenAddress',
    key: 'tokenAddress',
    width: 150,
  },
  {
    title: 'URL',
    dataIndex: 'dataUrl',
    key: 'dataUrl',
    width: 150,
  },
];

export const jobDetailsColumns = [
  {
    title: 'Item',
    dataIndex: 'prop',
    key: 'prop',
    width: 50,
  },
  {
    title: 'Value',
    dataIndex: 'value',
    key: 'value',
    width: 300,
  },
];

export enum PropName {
  Created = 'createdAt',
  Updated = 'updatedAt',
  Status = 'status',
  Id = 'id',
  Fee = 'fee',
  ReputationOracleUrl = 'reputationOracleUrl',
  ExchangeOracleAddress = 'exchangeOracleAddress',
  ExchangeOracleUrl = 'exchangeOracleUrl',
  RecordingOracleStake = 'recordingOracleStake',
  ReputationOracleStake = 'reputationOracleStake',
  NetworkId = 'networkId',
  EscrowAddress = 'escrowAddress',
  DataUrl = 'dataUrl',
  Data = 'data',
  ManifestHash = 'manifestHash',
  DatasetLength = 'datasetLength',
  AnnotationsPerImage = 'annotationsPerImage',
  Labels = 'labels',
  RequesterAccuracyTarget = 'requesterAccuracyTarget',
  RequesterDescription = 'requesterDescription',
  RecordingOracleAddress = 'recordingOracleAddress',
  UserId = 'userId',
  TokenAddress = 'tokenAddress',
  Price = 'price',
  Mode = 'mode',
  RequestType = 'requestType',
  RecordingOracleUrl = 'recordingOracleUrl',
  ReputationOracleAddress = 'reputationOracleAddress',
  // RequesterQuestion = 'requesterQuestion',
  // RequesterQuestionExample = 'requesterQuestionExample',
  // ReputationOracleStake = 'reputationOracleStake'
}

export enum ColumnTitle {
  Created = 'Created At',
  Fee = 'Fee',
  EscrowAddress = 'Escrow Address',
  RecordingOracleStake = 'Recording Oracle Stake',
  ReputationOracleStake = 'Reputation Oracle Stake',
  ExchangeOracleAddress = 'Exchange Oracle Address',
  RecordingOracleUrl = 'Recording Oracle Url',
  ExchangeOracleUrl = 'Exchange Oracle Url',
  Updated = 'Updated At',
  ReputationOracleUrl = 'Reputation Oracle Url',
  ReputationOracleAddress = 'Reputation Oracle Address',
  Status = 'Status',
  Id = 'Id',
  UserId = 'User Id',
  RecordingOracleAddress = 'Recording Oracle Address',
  RequesterDescription = 'Requester Description',
  NetworkId = 'Network Id',
  DataUrl = 'Url',
  Data = 'Data',
  ManifestHash = 'Manifest Hash',
  DatasetLength = 'Dataset Length',
  AnnotationsPerImage = 'Annotations Per Image',
  Labels = 'Labels',
  RequesterAccuracyTarget = 'Requester Accuracy Target',
  TokenAddress = 'Token Address',
  Price = 'Price',
  Mode = 'Mode',
  RequestType = 'Request Type',
  // RequesterQuestion = 'Requester Question',
  // RequesterQuestionExample = 'Requester Question Example',
}
